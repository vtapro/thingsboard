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

    /**
     * Server attribute that remembers which user created an entity (see DeviceController.saveRbacOwner).
     */
    public static final String RBAC_OWNER_ATTRIBUTE = "rbacOwnerId";

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
            permissionDenied();
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
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entityId)) {
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
    private boolean isEntityOwner(SecurityUser user, EntityId entityId) {
        try {
            return attributesService.find(user.getTenantId(), entityId, AttributeScope.SERVER_SCOPE, RBAC_OWNER_ATTRIBUTE)
                    .get(5, TimeUnit.SECONDS)
                    .map(attribute -> user.getId().getId().toString().equals(attribute.getStrValue().orElse(null)))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[{}] Failed to read the owner of the entity {}", user.getTenantId(), entityId, e);
            return false;
        }
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> void checkPermission(SecurityUser user, Resource resource, Operation operation,
                                                                           I entityId, T entity) throws ThingsboardException {
        if (!hasPermission(user, resource, operation, entityId, entity)) {
            permissionDenied();
        }
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
     * The operations of the legacy role model: the auxiliary operations of the same entity are derived.
     */
    private static final Set<String> LEGACY_OPERATIONS = Set.of("READ", "WRITE", "DELETE");

    /**
     * Resolves the operation that must be present in the role for the requested {@code operation}:
     * <ul>
     *   <li>role without any operation for this resource -> not restricted here;</li>
     *   <li>role with the requested operation (or ALL) -> granted;</li>
     *   <li>role that only declares READ/WRITE/DELETE (legacy) -> keep the derived behaviour, so
     *       existing roles are not affected by the detailed matrix;</li>
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
        boolean legacyRole = configured.stream().allMatch(LEGACY_OPERATIONS::contains);
        if (legacyRole) {
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

    private void permissionDenied() throws ThingsboardException {
        throw new ThingsboardException(PERMISSION_DENIED_MESSAGE,
                ThingsboardErrorCode.PERMISSION_DENIED);
    }

    private record CacheEntry<T>(T value, long expiresAt) {
    }

}
