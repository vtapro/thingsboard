// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.dao.settings.CustomerHierarchyService;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.common.data.AttributeScope;
import com.fasterxml.jackson.databind.JsonNode;
import org.thingsboard.server.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.entityview.EntityViewService;
import java.util.HashMap;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.thingsboard.server.common.data.security.Authority.SYS_ADMIN;

/**
 * Custom RBAC enforcement, disabled by default.
 *
 * <p>When {@code security.rbac.enabled=true}:
 * <ul>
 *   <li>a user that is assigned to one of the tenant custom roles gets the permissions of the effective role;
 *   <li>a user without a custom role (or a user for which the settings can not be read) keeps the platform
 *       default permission matrix, so the platform behaviour is unchanged;
 *   <li>custom roles may only <b>narrow</b> the access, never widen it across tenants or customers. The only
 *       exception is a customer user with a role that enables {@code ownCustomerOnly}: the user may then also
 *       access the sub-customers configured by the tenant administrator in the customer hierarchy.
 * </ul>
 *
 * <p>When the flag is off this bean is not created at all, so the platform behaviour is unchanged.
 * Removing the custom RBAC is a matter of deleting this class together with the {@code rbac} data classes,
 * the {@code *Service} settings classes and the related controllers.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(value = "security.rbac.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TbRbacAccessControlService implements AccessControlService {

    private static final String PERMISSION_DENIED_MESSAGE = "You don't have permission to perform this operation!";

    /**
     * Tenant RBAC settings are stored as JSON documents; the short-lived cache keeps the permission check of the
     * request hot path off the database. Set the TTL to 0 to disable the cache.
     */
    private static final long CACHE_TTL_MS = 10_000;
    private static final int CACHE_MAX_ENTRIES = 2_048;

    private final DefaultAccessControlService defaultAccessControlService;
    private final RoleService roleService;
    private final EntityGroupService entityGroupService;
    private final CustomerHierarchyService customerHierarchyService;
    private final AttributesService attributesService;
    private final DeviceService deviceService;
    private final AssetService assetService;
    private final EntityViewService entityViewService;

    /**
     * Server attribute that remembers which user created an entity (see DeviceController.saveRbacOwner).
     */
    public static final String RBAC_OWNER_ATTRIBUTE = "rbacOwnerId";

    /**
     * Owner index of the devices of a tenant: device id -> owner user id. It is rebuilt at most once
     * per {@link #OWNER_INDEX_TTL_MS} and invalidated when a device is created, so listing devices
     * for a role with the "own entities only" flag does not read the attributes entity by entity.
     */
    private final Map<String, OwnerIndex> ownerIndexCache = new ConcurrentHashMap<>();
    private static final long OWNER_INDEX_TTL_MS = 60_000L;
    private static final int OWNER_INDEX_PAGE_SIZE = 1000;

    private final Map<String, CacheEntry<Optional<RbacRole>>> effectiveRoleCache = new ConcurrentHashMap<>();
    private final Map<TenantId, CacheEntry<Optional<List<RbacEntityGroup>>>> entityGroupCache = new ConcurrentHashMap<>();

    @Override
    public boolean hasPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        RbacRole role = getEffectiveRole(user);
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return defaultAccessControlService.hasPermission(user, resource, operation);
        }
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            // The role declares detailed operations and this one is not granted.
            return false;
        }
        return (hasGlobalOperation(role, resource, effective)
                || hasScopedOperation(role, resource, effective))
                && defaultAccessControlService.hasPermission(user, resource, operation);
    }

    @Override
    public Set<UUID> getAllowedEntityIds(SecurityUser user, Resource resource, Operation operation) {
        RbacRole role = getEffectiveRole(user);
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return null;
        }
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            return Set.of();
        }
        if (isOwnOnlyResource(role, resource)) {
            // "Only entities created by the user": the list is limited to the entities owned by this user.
            Set<UUID> ownedIds = getOwnedEntityIds(user, resource);
            List<String> ownScopedGroups = getScopedGroups(role, resource, effective);
            if (ownScopedGroups != null && !ownScopedGroups.isEmpty()) {
                ownedIds.retainAll(groupEntityIds(user.getTenantId(), ownScopedGroups));
            }
            return ownedIds;
        }
        if (hasGlobalOperation(role, resource, effective)) {
            return null;
        }
        List<String> scopedGroups = getScopedGroups(role, resource, effective);
        if (scopedGroups == null || scopedGroups.isEmpty()) {
            return null;
        }
        Set<UUID> allowedIds = new HashSet<>();
        try {
            for (RbacEntityGroup group : getEntityGroups(user.getTenantId())) {
                if (scopedGroups.contains(group.getId()) && group.getEntityIds() != null) {
                    for (String entityId : group.getEntityIds()) {
                        try {
                            allowedIds.add(UUID.fromString(entityId));
                        } catch (IllegalArgumentException e) {
                            log.warn("Entity group [{}] contains an invalid entity id [{}]", group.getId(), entityId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve the entity groups of user [{}]: {}", user.getId(), e.getMessage());
        }
        return allowedIds;
    }

    @Override
    public void checkPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        if (!hasPermission(user, resource, operation)) {
            RbacRole role = getEffectiveRole(user);
            if (role != null && isResourceManagedByRole(role, resource)) {
                permissionDenied("Your role does not grant \"" + operationLabel(operation) + "\" on " + resource + ".");
            }
            permissionDenied(PERMISSION_DENIED_MESSAGE);
        }
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> boolean hasPermission(SecurityUser user, Resource resource, Operation operation,
                                                                            I entityId, T entity) throws ThingsboardException {
        RbacRole role = getEffectiveRole(user);
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity);
        }
        if (user.getCustomerId() != null) {
            return hasCustomerUserPermission(user, resource, operation, entityId, entity, role);
        }
        if (entity != null && !user.getTenantId().equals(entity.getTenantId())) {
            return false;
        }
        // "Only entities created by the user": the entity has to be owned by this user.
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entity)) {
            return false;
        }
        return hasOperationGrant(user.getTenantId(), role, resource, operation, entityId);
    }

    /**
     * True when the role limits this resource to the entities created by the user itself.
     */
    private static boolean isOwnOnlyResource(RbacRole role, Resource resource) {
        return role.getOwnOnly() != null && Boolean.TRUE.equals(role.getOwnOnly().get(resource.name()));
    }

    /**
     * The entity belongs to the user when its server attribute "rbacOwnerId" is the user id.
     */
    private static boolean isEntityOwner(SecurityUser user, Object entity) {
        if (!(entity instanceof BaseDataWithAdditionalInfo<?> data)) {
            return false;
        }
        JsonNode info = data.getAdditionalInfo();
        JsonNode owner = info == null ? null : info.get(RBAC_OWNER_ATTRIBUTE);
        return owner != null && !owner.isNull()
                && user.getId() != null && user.getId().getId().toString().equals(owner.asText());
    }

    /**
     * Ids of the entities that were created by the given user (used to filter the list pages).
     */
    private Set<UUID> getOwnedEntityIds(SecurityUser user, Resource resource) {
        String cacheKey = user.getTenantId().getId() + ":" + resource.name();
        OwnerIndex index = ownerIndexCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (index == null || now - index.createdTs > OWNER_INDEX_TTL_MS) {
            index = new OwnerIndex(now, loadOwnerIndex(user.getTenantId(), resource));
            ownerIndexCache.put(cacheKey, index);
        }
        String userId = user.getId().getId().toString();
        Set<UUID> result = new HashSet<>();
        index.owners.forEach((entityId, owner) -> {
            if (userId.equals(owner)) {
                result.add(entityId);
            }
        });
        return result;
    }

    /**
     * Index of the entities of one entity type that carry the server attribute "rbacOwnerId" (see saveRbacOwner in
     * BaseController), used to filter the list pages of a role with the "only entities created by the user" flag.
     */
    private Map<UUID, String> loadOwnerIndex(TenantId tenantId, Resource resource) {
        Map<UUID, String> owners = new HashMap<>();
        PageLink pageLink = new PageLink(OWNER_INDEX_PAGE_SIZE);
        try {
            boolean hasNext;
            do {
                PageData<? extends BaseDataWithAdditionalInfo<?>> page = findOwnerPage(tenantId, resource, pageLink);
                if (page == null) {
                    return owners;
                }
                for (BaseDataWithAdditionalInfo<?> entity : page.getData()) {
                    JsonNode info = entity.getAdditionalInfo();
                    JsonNode owner = info == null ? null : info.get(RBAC_OWNER_ATTRIBUTE);
                    if (owner != null && !owner.isNull() && !owner.asText().isBlank()) {
                        owners.put(entity.getId().getId(), owner.asText());
                    }
                }
                hasNext = page.hasNext();
                if (hasNext) {
                    pageLink = pageLink.nextPageLink();
                }
            } while (hasNext);
        } catch (Exception e) {
            log.warn("[{}] Failed to build the {} owner index", tenantId, resource, e);
        }
        return owners;
    }

    /**
     * One page of the entities of the resource. Only the entity types that store their owner in the additional info
     * support the "only entities created by the user" scope.
     */
    private PageData<? extends BaseDataWithAdditionalInfo<?>> findOwnerPage(TenantId tenantId, Resource resource,
                                                                           PageLink pageLink) {
        return switch (resource) {
            case DEVICE -> deviceService.findDevicesByTenantId(tenantId, pageLink);
            case ASSET -> assetService.findAssetsByTenantId(tenantId, pageLink);
            case ENTITY_VIEW -> entityViewService.findEntityViewByTenantId(tenantId, pageLink);
            default -> {
                log.warn("[{}] The resource {} does not support the \"own entities\" scope", tenantId, resource);
                yield null;
            }
        };
    }

    /**
     * Drops the cached owner index of the tenant (called when a device is created).
     */
    public void invalidateOwnerIndex(TenantId tenantId) {
        String prefix = tenantId.getId() + ":";
        ownerIndexCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private record OwnerIndex(long createdTs, Map<UUID, String> owners) {
    }

    private Set<UUID> groupEntityIds(TenantId tenantId, List<String> groupIds) {
        Set<UUID> ids = new HashSet<>();
        for (RbacEntityGroup group : getEntityGroups(tenantId)) {
            if (groupIds.contains(group.getId()) && group.getEntityIds() != null) {
                for (String entityId : group.getEntityIds()) {
                    try {
                        ids.add(UUID.fromString(entityId));
                    } catch (IllegalArgumentException e) {
                        log.warn("Entity group [{}] contains an invalid entity id [{}]", group.getId(), entityId);
                    }
                }
            }
        }
        return ids;
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> void checkPermission(SecurityUser user, Resource resource, Operation operation,
                                                                           I entityId, T entity) throws ThingsboardException {
        if (!hasPermission(user, resource, operation, entityId, entity)) {
            permissionDenied(denialReason(user, resource, operation, entityId, entity));
        }
    }

    /**
     * Human readable explanation of a denied request. The WEB UI shows it in the dialog, so the administrator (and
     * the end user) knows whether the operation is missing, the entity is not in the granted groups or the user is
     * not the owner of the entity.
     */
    private <I extends EntityId, T extends HasTenantId> String denialReason(SecurityUser user, Resource resource,
                                                                           Operation operation, I entityId, T entity) {
        RbacRole role = getEffectiveRole(user);
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return PERMISSION_DENIED_MESSAGE;
        }
        String entityLabel = entityLabel(entityId, resource);
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entity)) {
            if (entity != null) {
                return "You are not the owner of this " + entityLabel
                        + ". Only the user that created it can read or change it.";
            }
        }
        if (resolveOperation(role, resource, operation) == null) {
            return "Your role does not grant \"" + operationLabel(operation) + "\" on " + resource + ".";
        }
        if (entityId != null) {
            List<String> scopedGroups = getScopedGroups(role, resource, grantedOperation(operation));
            if (scopedGroups != null && !scopedGroups.isEmpty()
                    && !entityBelongsToGroups(user.getTenantId(), entityId, scopedGroups)) {
                return "This " + entityLabel + " is not a member of the entity groups granted to your role.";
            }
        }
        if (user.getCustomerId() != null) {
            return "This " + entityLabel + " does not belong to your customer, so your role can not grant access to it.";
        }
        return PERMISSION_DENIED_MESSAGE;
    }

    private static String entityLabel(EntityId entityId, Resource resource) {
        String name = entityId != null ? entityId.getEntityType().name() : resource.name();
        return name.replace('_', ' ').toLowerCase();
    }

    /**
     * READ_CREDENTIALS -> "Read credentials".
     */
    private static String operationLabel(Operation operation) {
        String label = operation.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    /**
     * Customer users keep the platform customer isolation: the custom role may extend the operations of the user,
     * but never grant access to entities of another customer.
     */
    private <I extends EntityId, T extends HasTenantId> boolean hasCustomerUserPermission(SecurityUser user, Resource resource,
                                                                                         Operation operation, I entityId, T entity,
                                                                                         RbacRole role) throws ThingsboardException {
        // An entity that the administrator explicitly put into a group granted to this role is accessible even when
        // it is not assigned to the customer of the user (the same way the public entity groups of PE work).
        Set<UUID> allowedEntityIds = getAllowedEntityIds(user, resource, operation);
        if (entityId != null && allowedEntityIds != null && allowedEntityIds.contains(entityId.getId())) {
            return entity != null && user.getTenantId().equals(entity.getTenantId());
        }
        if (defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity)) {
            return true;
        }
        if (!role.isOwnCustomerOnly() || !hasOperationGrant(user.getTenantId(), role, resource, operation, entityId)) {
            return false;
        }
        return userBelongsToCustomerSubtree(user, entity);
    }

    private boolean hasScopedOperation(RbacRole role, Resource resource, Operation operation) {
        List<String> scopedGroups = getScopedGroups(role, resource, operation);
        return scopedGroups != null && !scopedGroups.isEmpty();
    }

    /**
     * True when the custom role configures this resource. Only the configured resources are restricted by the role:
     * the auxiliary resources that the WEB UI needs (device profile, telemetry, attributes, widgets, ...) keep the
     * platform permissions, otherwise a role limited to devices would break the entity details pages.
     */
    private boolean isResourceManagedByRole(RbacRole role, Resource resource) {
        String name = resource.name();
        return (role.getPermissions() != null && role.getPermissions().containsKey(name))
                || (role.getScopedPermissions() != null && role.getScopedPermissions().containsKey(name));
    }

    /**
     * Only the entities that can be a member of an entity group may be scoped to groups.
     */
    public static final Set<String> GROUP_SCOPED_RESOURCES = Set.of("DEVICE", "ASSET", "ENTITY_VIEW");

    /**
     * The role configures the operations READ, WRITE and DELETE. The auxiliary operations of the same entity
     * (telemetry, attributes, credentials, rpc, claim, assign, calculated fields) are mapped to the configured one.
     */
    private static Operation grantedOperation(Operation operation) {
        if (operation == Operation.READ || operation == Operation.WRITE || operation == Operation.DELETE) {
            return operation;
        }
        // Everything that reads (attributes, telemetry, credentials, calculated fields) requires READ,
        // every other auxiliary operation (rpc, claim, assign, create, ALL) requires WRITE.
        return operation.name().startsWith("READ") ? Operation.READ : Operation.WRITE;
    }

    /**
     * The basic operations of an entity type. A role that only uses operations from this set is a "classic" role and
     * keeps the derived behaviour (see {@link #grantedOperation(Operation)}), so the entity details pages keep
     * working. CREATE belongs here because the permission matrix of the WEB UI stores the four basic operations as
     * soon as the administrator grants full access to an entity type.
     */
    private static final Set<String> CLASSIC_OPERATIONS = Set.of("CREATE", "READ", "WRITE", "DELETE");

    /**
     * Credentials are never derived from READ/WRITE: they expose the access token of a device, so the administrator
     * has to grant them explicitly (or with {@code ALL}).
     */
    private static final Set<Operation> CREDENTIAL_OPERATIONS =
            Set.of(Operation.READ_CREDENTIALS, Operation.WRITE_CREDENTIALS);

    /**
     * Resolves the operation that must be present in the role for the requested {@code operation}:
     * <ul>
     *   <li>role without any operation for this resource -> not restricted here;</li>
     *   <li>role with the requested operation (or ALL) -> granted;</li>
     *   <li>role that only declares the basic operations CREATE/READ/WRITE/DELETE -> keep the derived behaviour,
     *       so existing roles are not affected by the detailed matrix;</li>
     *   <li>credentials -> never derived, they have to be listed explicitly;</li>
     *   <li>role with detailed operations -> granted only when listed explicitly.</li>
     * </ul>
     *
     * @return the operation to look up in the role, or null when the role does not grant it.
     */
    private static Operation resolveOperation(RbacRole role, Resource resource, Operation operation) {
        List<String> configured = configuredOperations(role, resource);
        if (configured.isEmpty()) {
            return operation;
        }
        if (configured.contains(operation.name()) || configured.contains(Operation.ALL.name())) {
            return operation;
        }
        if (CREDENTIAL_OPERATIONS.contains(operation)) {
            return null;
        }
        boolean classicRole = configured.stream().allMatch(CLASSIC_OPERATIONS::contains);
        if (classicRole) {
            Operation derived = grantedOperation(operation);
            return configured.contains(derived.name()) ? derived : null;
        }
        return null;
    }

    private static List<String> configuredOperations(RbacRole role, Resource resource) {
        String resourceName = resource.name();
        List<String> result = new ArrayList<>();
        if (role.getPermissions() != null && role.getPermissions().get(resourceName) != null) {
            result.addAll(role.getPermissions().get(resourceName));
        }
        if (role.getScopedPermissions() != null && role.getScopedPermissions().get(resourceName) != null) {
            role.getScopedPermissions().get(resourceName).forEach((operation, groups) -> {
                if (groups != null && !groups.isEmpty()) {
                    result.add(operation);
                }
            });
        }
        return result;
    }

    private RbacRole getEffectiveRole(SecurityUser user) {
        if (user == null || user.getId() == null || user.getTenantId() == null || SYS_ADMIN.equals(user.getAuthority())) {
            return null;
        }
        String userId = user.getId().getId().toString();
        String cacheKey = user.getTenantId().getId() + ":" + userId;
        try {
            return cached(effectiveRoleCache, cacheKey,
                    () -> Optional.ofNullable(roleService.getEffectiveRole(user.getTenantId(), userId))).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to load RBAC roles for user [{}], falling back to default permissions: {}",
                    user.getId(), e.getMessage(), e);
        }
        return null;
    }

    /**
     * The role grants the operation when the operation is granted globally, or when the operation is granted
     * on one of the entity groups the entity belongs to.
     */
    private boolean hasOperationGrant(TenantId tenantId, RbacRole role, Resource resource, Operation operation, EntityId entityId) {
        Operation grantedOperation = grantedOperation(operation);
        if (hasGlobalOperation(role, resource, grantedOperation)) {
            return true;
        }
        List<String> scopedGroups = getScopedGroups(role, resource, grantedOperation);
        return entityId != null && scopedGroups != null && !scopedGroups.isEmpty()
                && entityBelongsToGroups(tenantId, entityId, scopedGroups);
    }

    private boolean hasGlobalOperation(RbacRole role, Resource resource, Operation operation) {
        Map<String, List<String>> permissions = role.getPermissions();
        if (permissions == null) {
            return false;
        }
        List<String> operations = permissions.get(resource.name());
        return operations != null && operations.contains(operation.name());
    }

    private List<String> getScopedGroups(RbacRole role, Resource resource, Operation operation) {
        Map<String, Map<String, List<String>>> scoped = role.getScopedPermissions();
        if (scoped == null) {
            return null;
        }
        Map<String, List<String>> byOperation = scoped.get(resource.name());
        return byOperation != null ? byOperation.get(operation.name()) : null;
    }

    private boolean entityBelongsToGroups(TenantId tenantId, EntityId entityId, List<String> groupIds) {
        try {
            String id = entityId.getId().toString();
            for (RbacEntityGroup group : getEntityGroups(tenantId)) {
                if (groupIds.contains(group.getId()) && group.getEntityIds() != null && group.getEntityIds().contains(id)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve entity groups for entity [{}]: {}", entityId, e.getMessage());
        }
        return false;
    }

    private List<RbacEntityGroup> getEntityGroups(TenantId tenantId) {
        return cached(entityGroupCache, tenantId,
                () -> Optional.ofNullable(entityGroupService.getEntityGroupSettings(tenantId).getGroups()))
                .orElse(List.of());
    }

    /**
     * True when the entity belongs to the customer of the user or to any of its sub-customers.
     */
    private boolean userBelongsToCustomerSubtree(SecurityUser user, HasTenantId entity) {
        if (user.getCustomerId() == null || !(entity instanceof HasCustomerId)) {
            return false;
        }
        try {
            var entityCustomerId = ((HasCustomerId) entity).getCustomerId();
            if (entityCustomerId == null || entityCustomerId.getId() == null) {
                return false;
            }
            var subtree = customerHierarchyService.getCustomerSubtree(user.getTenantId(),
                    user.getCustomerId().getId().toString());
            return subtree.contains(entityCustomerId.getId().toString());
        } catch (Exception e) {
            log.warn("Failed to resolve customer subtree for user [{}]: {}", user.getId(), e.getMessage());
            return false;
        }
    }

    private <K, V> Optional<V> cached(Map<K, CacheEntry<Optional<V>>> cache, K key, Supplier<Optional<V>> loader) {
        long now = System.currentTimeMillis();
        if (CACHE_TTL_MS > 0) {
            CacheEntry<Optional<V>> entry = cache.get(key);
            if (entry != null && entry.expiresAt() > now) {
                return entry.value();
            }
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.values().removeIf(stale -> stale.expiresAt() <= now);
            }
        }
        Optional<V> value = loader.get();
        if (CACHE_TTL_MS > 0) {
            cache.put(key, new CacheEntry<>(value, now + CACHE_TTL_MS));
        }
        return value;
    }

    private void permissionDenied(String message) throws ThingsboardException {
        throw new ThingsboardException(message, ThingsboardErrorCode.PERMISSION_DENIED);
    }

    private record CacheEntry<T>(T value, long expiresAt) {
    }

}
