// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacShare;
import org.thingsboard.server.common.data.rbac.RbacUserGroup;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.settings.ShareService;
import org.thingsboard.server.dao.settings.UserGroupService;
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
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.entityview.EntityViewService;
import org.thingsboard.server.dao.user.UserService;
import java.util.HashMap;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>a user without a custom role keeps the platform default permission matrix, so the platform behaviour is
 *       unchanged;
 *   <li>when the RBAC settings of the tenant can not be read the request is denied (fail closed) instead of falling
 *       back to the default permission matrix, which would widen the access of a narrowed role;
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
     * Shown when the RBAC settings of the tenant can not be read: the request is denied instead of silently
     * falling back to the platform permission matrix, otherwise a corrupted settings document would grant the
     * users more than their role allows.
     */
    private static final String SETTINGS_UNAVAILABLE_MESSAGE =
            "Your role can not be resolved: the RBAC settings of the tenant are not readable. "
                    + "Ask the tenant administrator to check the roles configuration.";

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
    private final ShareService shareService;
    private final UserGroupService userGroupService;
    private final UserService userService;
    private final CustomerService customerService;

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
    private static final int OWNER_INDEX_MAX_ENTRIES = 256;

    private final Map<String, CacheEntry<Optional<EffectiveRole>>> effectiveRoleCache = new ConcurrentHashMap<>();
    private final Map<TenantId, CacheEntry<Optional<List<RbacEntityGroup>>>> entityGroupCache = new ConcurrentHashMap<>();
    private final Map<TenantId, CacheEntry<Optional<List<RbacShare>>>> shareCache = new ConcurrentHashMap<>();
    private final Map<TenantId, CacheEntry<Optional<List<RbacUserGroup>>>> userGroupCache = new ConcurrentHashMap<>();

    @Override
    public boolean hasPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        EffectiveRole effectiveRole = resolveEffectiveRole(user);
        if (effectiveRole.settingsUnavailable()) {
            return false;
        }
        RbacRole role = effectiveRole.role();
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return defaultAccessControlService.hasPermission(user, resource, operation);
        }
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            // The role declares detailed operations and this one is not granted.
            return false;
        }
        if (!hasGlobalOperation(role, resource, effective) && !hasScopedOperation(role, resource, effective)) {
            return false;
        }
        if (isMemberResource(resource)) {
            // The platform never lets a customer user manage the members of its customer; the tenant administrator
            // opts in by granting the operation in a custom role. The customer scope is enforced on the entity
            // aware checks, so the entity less check only has to verify the role grant.
            return true;
        }
        return defaultAccessControlService.hasPermission(user, resource, operation);
    }

    @Override
    public boolean hasCustomRole(SecurityUser user) {
       return resolveEffectiveRole(user).role() != null;
    }

    @Override
    public Set<UUID> getAllowedEntityIds(SecurityUser user, Resource resource, Operation operation) {
        if (resolveEffectiveRole(user).settingsUnavailable()) {
            // fail closed: without a readable role nothing is listed
            return Set.of();
        }
        Set<UUID> allowed = roleAllowedEntityIds(user, resource, operation);
        Set<UUID> shared = getSharedEntityIds(user, resource, operation);
        if (shared.isEmpty() || allowed == null) {
            // null means "the role does not filter the list of this resource"
            return allowed;
        }
        Set<UUID> result = new HashSet<>(allowed);
        result.addAll(shared);
        return result;
    }

    private Set<UUID> roleAllowedEntityIds(SecurityUser user, Resource resource, Operation operation) {
        EffectiveRole effectiveRole = resolveEffectiveRole(user);
        if (effectiveRole.settingsUnavailable()) {
            return Set.of();
        }
        RbacRole role = effectiveRole.role();
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
            if (ownScopedGroups != null && !ownScopedGroups.isEmpty()
                    && !containsAllGroup(user.getTenantId(), resource, ownScopedGroups)) {
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
        if (containsAllGroup(user.getTenantId(), resource, scopedGroups)) {
            // the role is scoped to the "All" group of the entity type: everything matches, so there is no filter
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

    /**
     * Ids of the customers the user may see: its own customer and, when the role enables the customer hierarchy,
     * the sub-customers configured by the tenant administrator.
     */
    @Override
    public Set<UUID> getAccessibleCustomerIds(SecurityUser user) {
        if (!isCustomerUser(user)) {
            return null;
        }
        RbacRole role = resolveEffectiveRole(user).role();
        if (role == null
                || (!isResourceManagedByRole(role, Resource.USER) && !isResourceManagedByRole(role, Resource.CUSTOMER))) {
            return null;
        }
        Set<UUID> customerIds = new HashSet<>();
        customerIds.add(user.getCustomerId().getId());
        if (role.isOwnCustomerOnly()) {
            try {
                for (String id : customerHierarchyService.getCustomerSubtree(user.getTenantId(),
                        user.getCustomerId().getId().toString())) {
                    try {
                        customerIds.add(UUID.fromString(id));
                    } catch (IllegalArgumentException e) {
                        log.warn("[{}] Invalid customer id [{}] in the customer hierarchy", user.getTenantId(), id);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve the customer subtree of user [{}]: {}", user.getId(), e.getMessage());
            }
        }
        return customerIds;
    }

    @Override
    public void checkPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        if (!hasPermission(user, resource, operation)) {
            EffectiveRole effectiveRole = resolveEffectiveRole(user);
            if (effectiveRole.settingsUnavailable()) {
                permissionDenied(SETTINGS_UNAVAILABLE_MESSAGE);
            }
            RbacRole role = effectiveRole.role();
            if (role != null && isResourceManagedByRole(role, resource)) {
                permissionDenied("Your role does not grant \"" + operationLabel(operation) + "\" on " + resource + ".");
            }
            permissionDenied(PERMISSION_DENIED_MESSAGE);
        }
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> boolean hasPermission(SecurityUser user, Resource resource, Operation operation,
                                                                            I entityId, T entity) throws ThingsboardException {
        if (isSharedWith(user, entityId, operation)) {
            // an explicit share configured by the tenant administrator grants the operation to the user;
            // the shares of a tenant only reference the entities of that tenant. The check fails closed: an entity
            // that is not loaded, or that has no tenant, is never treated as shared.
            return entity != null && entity.getTenantId() != null && user.getTenantId().equals(entity.getTenantId());
        }
        EffectiveRole effectiveRole = resolveEffectiveRole(user);
        if (effectiveRole.settingsUnavailable()) {
            return false;
        }
        RbacRole role = effectiveRole.role();
        if (role == null || !isResourceManagedByRole(role, resource)) {
            return defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity);
        }
        if (entity != null && entity.getTenantId() != null && !user.getTenantId().equals(entity.getTenantId())) {
            return false;
        }
        if (isMemberResource(resource) && isCustomerUser(user)) {
            return hasCustomerMemberPermission(user, resource, operation, entityId, entity, role);
        }
        if (isCustomerUser(user)) {
            return hasCustomerUserPermission(user, resource, operation, entityId, entity, role);
        }
        // "Only entities created by the user": the entity has to be owned by this user.
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entity)) {
            return false;
        }
        // The detailed matrix is enforced here as well: the role has to grant the operation (or derive it from the
        // basic operations when it is a "classic" role), otherwise only the entity-less checks would be strict.
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            return false;
        }
        return hasOperationGrant(user.getTenantId(), role, resource, effective, entityId);
    }

    /**
     * True when the role limits this resource to the entities created by the user itself.
     */
    private static boolean isOwnOnlyResource(RbacRole role, Resource resource) {
        // the owner of an entity is stored in its additional info, so the flag only applies to the resources that
        // have one (a dashboard of a customer user is scoped by the customer that owns it instead)
        return supportsOwnerIndex(resource)
                && role.getOwnOnly() != null && Boolean.TRUE.equals(role.getOwnOnly().get(resource.name()));
    }

    /**
     * Resources whose entities can be limited to the ones created by the user: they carry the creator in the
     * additional info (see {@link #RBAC_OWNER_ATTRIBUTE}).
     */
    private static boolean supportsOwnerIndex(Resource resource) {
        return OWNER_INDEX_RESOURCES.contains(resource);
    }

    private static final Set<Resource> OWNER_INDEX_RESOURCES =
            Set.of(Resource.DEVICE, Resource.ASSET, Resource.ENTITY_VIEW, Resource.USER, Resource.CUSTOMER);

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
            evictOwnerIndexEntries(now);
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
     * Keeps the owner index cache bounded: an entry is only useful for {@link #OWNER_INDEX_TTL_MS}, so the stale ones
     * are dropped first and the oldest one when the cache is still full.
     */
    private void evictOwnerIndexEntries(long now) {
        if (ownerIndexCache.size() < OWNER_INDEX_MAX_ENTRIES) {
            return;
        }
        ownerIndexCache.values().removeIf(index -> now - index.createdTs > OWNER_INDEX_TTL_MS);
        if (ownerIndexCache.size() >= OWNER_INDEX_MAX_ENTRIES) {
            ownerIndexCache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdTs()))
                    .ifPresent(entry -> ownerIndexCache.remove(entry.getKey(), entry.getValue()));
        }
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
            case USER -> userService.findUsersByTenantId(tenantId, pageLink);
            case CUSTOMER -> customerService.findCustomersByTenantId(tenantId, pageLink);
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

    @Override
    public void onEntityCreated(SecurityUser user) {
        if (user != null && user.getTenantId() != null) {
            // the owner index of the tenant is stale as soon as one of its entities was created
            invalidateOwnerIndex(user.getTenantId());
        }
    }

    @Override
    public void onPermissionsChanged() {
        // roles, entity groups, user groups and shares are all part of the permission settings of a tenant
        effectiveRoleCache.clear();
        entityGroupCache.clear();
        userGroupCache.clear();
        shareCache.clear();
    }

    private record OwnerIndex(long createdTs, Map<UUID, String> owners) {
    }

    private Set<UUID> groupEntityIds(TenantId tenantId, List<String> groupIds) {
        Set<UUID> ids = new HashSet<>();
        for (RbacEntityGroup group : getEntityGroups(tenantId)) {
            if (groupIds.contains(group.getId()) && !group.isAllGroup() && group.getEntityIds() != null) {
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
        EffectiveRole effectiveRole = resolveEffectiveRole(user);
        if (effectiveRole.settingsUnavailable()) {
            return SETTINGS_UNAVAILABLE_MESSAGE;
        }
        RbacRole role = effectiveRole.role();
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
        if (isCustomerUser(user)) {
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
     * A customer user keeps the platform customer isolation and the custom role may only narrow it: the role has to
     * grant the operation, and an entity that is outside of the customer subtree of the user is accessible only when
     * the administrator put it into an entity group that is shared with every customer user (a public group).
     *
     * <p>The same rules are applied by {@link #roleAllowedEntityIds(SecurityUser, Resource, Operation)} for the list
     * pages, so a customer user never sees an entity in a list page that the item check denies (and the other way
     * around).
     */
    private <I extends EntityId, T extends HasTenantId> boolean hasCustomerUserPermission(SecurityUser user, Resource resource,
                                                                                         Operation operation, I entityId, T entity,
                                                                                         RbacRole role) throws ThingsboardException {
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            // the detailed matrix of the role does not grant this operation
            return false;
        }
        Set<UUID> allowedEntityIds = entityId == null ? null : roleAllowedEntityIds(user, resource, operation);
        if (allowedEntityIds != null) {
            // The role limits this resource to the entities of the granted groups (or to the entities created by the
            // user): the entity has to be one of them.
            if (entityId == null || !allowedEntityIds.contains(entityId.getId()) || entity == null
                    || !user.getTenantId().equals(entity.getTenantId())) {
                return false;
            }
            // An entity of another customer is only accessible when the administrator marked the group as public.
            if (!defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity)
                    && !userBelongsToCustomerSubtree(user, entity)
                    && !isInPublicGroup(role, user.getTenantId(), resource, effective, entityId)) {
                return false;
            }
            return true;
        }
        if (defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity)) {
            return true;
        }
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entity)) {
            // "Only entities created by the user": the customer user only reaches the entities it created itself
            return false;
        }
        if (!hasOperationGrant(user.getTenantId(), role, resource, effective, entityId)) {
            return false;
        }
        if (CUSTOMER_TENANT_WIDE_RESOURCES.contains(resource)) {
            // A dashboard is a tenant level entity that is assigned to the customer of its creator: an explicit role
            // grant of the tenant administrator is honored, and an existing dashboard has to be visible to the
            // customer user (that is, assigned to its customer).
            return entityId == null
                    || defaultAccessControlService.hasPermission(user, resource, Operation.READ, entityId, entity);
        }
        // A customer user keeps the platform isolation, the role may only widen it inside the customer scope of the
        // user: the entity has to belong to the own customer of the user or, when the role enables the customer
        // hierarchy, to one of the sub-customers configured by the tenant administrator. A new entity is created
        // inside the customer of the caller (the controller assigns it before this check).
        return belongsToOwnCustomer(user, entity)
                || (role.isOwnCustomerOnly() && userBelongsToCustomerSubtree(user, entity));
    }

    /**
     * Tenant level entities that a customer user may handle when its role grants the operation: they are assigned
     * to a customer instead of carrying the customer id of their owner (see the dashboard assignment API).
     */
    private static final Set<Resource> CUSTOMER_TENANT_WIDE_RESOURCES = Set.of(Resource.DASHBOARD);

    /**
     * True when the entity belongs to the own customer of the user. A new entity carries the customer assigned by
     * the controller, so the same check also covers the creation of an entity by a customer user.
     */
    private boolean belongsToOwnCustomer(SecurityUser user, Object entity) {
        if (!isCustomerUser(user) || !(entity instanceof HasCustomerId hasCustomerId)) {
            return false;
        }
        CustomerId customerId = hasCustomerId.getCustomerId();
        return customerId != null && customerId.getId() != null && customerId.equals(user.getCustomerId());
    }

    /**
     * Member management (users and customers) performed by a customer user. The role has to grant the operation
     * explicitly, then the entity is checked against the customer scope of the user: its own customer always, and
     * the sub-customers when the role enables the customer hierarchy. A role with the "only entities created by the
     * user" flag further limits the scope to the members created by the user itself.
     */
    private <I extends EntityId, T extends HasTenantId> boolean hasCustomerMemberPermission(SecurityUser user, Resource resource,
                                                                                            Operation operation, I entityId, T entity,
                                                                                            RbacRole role) {
        Operation effective = resolveOperation(role, resource, operation);
        if (effective == null) {
            return false;
        }
        if (!hasOperationGrant(user.getTenantId(), role, resource, effective, entityId)) {
            return false;
        }
        if (isOwnOnlyResource(role, resource) && entityId != null && !isEntityOwner(user, entity)) {
            return false;
        }
        if (Resource.USER == resource) {
            return canManageUser(user, operation, entity, role);
        }
        return canManageCustomer(user, operation, entityId, entity, role);
    }

    private boolean canManageUser(SecurityUser user, Operation operation, Object entity, RbacRole role) {
        if (entity instanceof User target) {
            if (target.getId() != null && target.getId().equals(user.getId())) {
                // every user keeps the right to read and update its own profile
                return true;
            }
            if (target.getAuthority() != null && target.getAuthority() != Authority.CUSTOMER_USER) {
                // a customer user never manages the administrators of the tenant
                return false;
            }
            if (target.getCustomerId() == null) {
                // creating a member: the controller assigns the customer of the caller
                return operation == Operation.CREATE;
            }
            return isCustomerInUserScope(user, target.getCustomerId(), role);
        }
        // the entity was not loaded (create): the controller assigns the customer of the caller
        return operation == Operation.CREATE;
    }

    private boolean canManageCustomer(SecurityUser user, Operation operation, EntityId entityId, Object entity, RbacRole role) {
        if (operation == Operation.CREATE && entityId == null) {
            // the controller records the new customer as a child of the customer of the caller
            return true;
        }
        if (entity instanceof Customer target) {
            if (operation == Operation.DELETE && user.getCustomerId() != null
                    && user.getCustomerId().equals(target.getId())) {
                // a customer user never deletes the customer it belongs to: it would delete its own scope
                // (and the account of the caller) with it
                return false;
            }
            return isCustomerInUserScope(user, target.getId(), role);
        }
        return false;
    }

    /**
     * True when the target customer is the customer of the user or one of its descendants (only when the role
     * enables the customer hierarchy).
     */
    private boolean isCustomerInUserScope(SecurityUser user, CustomerId targetCustomerId, RbacRole role) {
        if (!isCustomerUser(user) || targetCustomerId == null) {
            return false;
        }
        if (user.getCustomerId().equals(targetCustomerId)) {
            return true;
        }
        if (!role.isOwnCustomerOnly()) {
            return false;
        }
        try {
            return customerHierarchyService.getCustomerSubtree(user.getTenantId(),
                    user.getCustomerId().getId().toString()).contains(targetCustomerId.getId().toString());
        } catch (Exception e) {
            log.warn("Failed to resolve the customer subtree of user [{}]: {}", user.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * True when one of the groups that grant the operation is public, i.e. the entity is visible for every customer
     * user that is assigned to a role scoped to that group (see {@link RbacEntityGroup#isPublicGroup()}).
     */
    private boolean isInPublicGroup(RbacRole role, TenantId tenantId, Resource resource, Operation operation,
                                    EntityId entityId) {
        List<String> scopedGroups = getScopedGroups(role, resource, operation);
        if (scopedGroups == null || scopedGroups.isEmpty() || entityId == null) {
            return false;
        }
        String entity = entityId.getId().toString();
        String entityType = entityId.getEntityType().name();
        for (RbacEntityGroup group : getEntityGroups(tenantId)) {
            if (!group.isPublicGroup() || !scopedGroups.contains(group.getId())
                    || !entityType.equals(group.getEntityType())) {
                continue;
            }
            if (group.isAllGroup() || (group.getEntityIds() != null && group.getEntityIds().contains(entity))) {
                return true;
            }
        }
        return false;
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
    public static final Set<String> GROUP_SCOPED_RESOURCES = Set.of("DEVICE", "ASSET", "ENTITY_VIEW", "CUSTOMER", "USER");

    /**
     * Resources that describe the members of a customer (the users and the sub-customers). They are special because
     * the platform only lets the tenant administrator manage them; a custom role may explicitly grant the operation
     * to a customer user, the customer scope is then enforced by
     * {@link #hasCustomerMemberPermission(SecurityUser, Resource, Operation, EntityId, HasTenantId, RbacRole)}.
     */
    private static final Set<String> MEMBER_RESOURCES = Set.of(Resource.USER.name(), Resource.CUSTOMER.name());

    private static boolean isMemberResource(Resource resource) {
        return MEMBER_RESOURCES.contains(resource.name());
    }

    /**
     * True when the user really belongs to a customer. Tenant and system administrators carry the "null"
     * customer id (13814000-...) instead of a Java null, so a plain {@code getCustomerId() != null} check would
     * treat them as customer users and silently wrong-scope their entity checks and list queries.
     */
    private static boolean isCustomerUser(SecurityUser user) {
        return user != null && user.getCustomerId() != null && !user.getCustomerId().isNullUid();
    }

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

    private EffectiveRole resolveEffectiveRole(SecurityUser user) {
        if (user == null || user.getId() == null || user.getTenantId() == null || SYS_ADMIN.equals(user.getAuthority())) {
            return EffectiveRole.NONE;
        }
        String userId = user.getId().getId().toString();
        String cacheKey = user.getTenantId().getId() + ":" + userId;
        try {
            return cached(effectiveRoleCache, cacheKey, () -> {
                try {
                    return Optional.of(new EffectiveRole(roleService.getEffectiveRole(user.getTenantId(), userId), false));
                } catch (Exception e) {
                    log.error("[{}] Failed to read the RBAC roles of user [{}]; the request is denied instead of " +
                            "falling back to the default permissions: {}", user.getTenantId(), user.getId(),
                            e.getMessage(), e);
                    return Optional.of(new EffectiveRole(null, true));
                }
            }).orElse(EffectiveRole.NONE);
        } catch (Exception e) {
            log.error("[{}] Failed to resolve the RBAC roles of user [{}]: {}", user.getTenantId(), user.getId(),
                    e.getMessage(), e);
            return new EffectiveRole(null, true);
        }
    }

    /**
     * The role grants the operation when the operation is granted globally, or when the operation is granted
     * on one of the entity groups the entity belongs to. The caller passes the operation resolved by
     * {@link #resolveOperation(RbacRole, Resource, Operation)}, so the auxiliary operations are already mapped to the
     * operation that the role has to contain.
     */
    private boolean hasOperationGrant(TenantId tenantId, RbacRole role, Resource resource, Operation operation, EntityId entityId) {
        if (hasGlobalOperation(role, resource, operation)) {
            return true;
        }
        List<String> scopedGroups = getScopedGroups(role, resource, operation);
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
            String entityType = entityId.getEntityType().name();
            for (RbacEntityGroup group : getEntityGroups(tenantId)) {
                if (groupIds.contains(group.getId())) {
                    if (group.isAllGroup() && entityType.equals(group.getEntityType())) {
                        // the "All" group of the entity type always matches
                        return true;
                    }
                    if (group.getEntityIds() != null && group.getEntityIds().contains(id)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve entity groups for entity [{}]: {}", entityId, e.getMessage());
        }
        return false;
    }

    /** True when one of the groups is the "All" group of the resource (it matches every entity of that type). */
    private boolean containsAllGroup(TenantId tenantId, Resource resource, List<String> groupIds) {
        for (RbacEntityGroup group : getEntityGroups(tenantId)) {
            if (groupIds.contains(group.getId()) && group.isAllGroup()
                    && resource.name().equals(group.getEntityType())) {
                return true;
            }
        }
        return false;
    }

    private List<RbacEntityGroup> getEntityGroups(TenantId tenantId) {
        return cached(entityGroupCache, tenantId,
                () -> Optional.ofNullable(entityGroupService.getEntityGroupSettings(tenantId).getGroups()))
                .orElse(List.of());
    }

    private List<RbacShare> getShares(TenantId tenantId) {
        return cached(shareCache, tenantId, () -> {
            var settings = shareService.getShareSettings(tenantId);
            return Optional.ofNullable(settings == null ? null : settings.getShares());
        }).orElse(List.of());
    }

    private List<RbacUserGroup> getUserGroups(TenantId tenantId) {
        return cached(userGroupCache, tenantId, () -> {
            var settings = userGroupService.getUserGroupSettings(tenantId);
            return Optional.ofNullable(settings == null ? null : settings.getGroups());
        }).orElse(List.of());
    }

    private Set<String> getUserGroupIds(SecurityUser user) {
        String userId = user.getId().getId().toString();
        Set<String> ids = new HashSet<>();
        for (RbacUserGroup group : getUserGroups(user.getTenantId())) {
            if (group.getUserIds() != null && group.getUserIds().contains(userId)) {
                ids.add(group.getId());
            }
        }
        return ids;
    }

    /**
     * True when the share grants the operation. {@code ALL} is honoured like in the permission matrix of a role,
     * but a share never grants the credentials operations: the share is checked before the role, so it must not be
     * able to grant an operation that the role model deliberately never derives from READ/WRITE.
     */
    private static boolean shareGrants(RbacShare share, Operation operation) {
        if (operation == null || CREDENTIAL_OPERATIONS.contains(operation)) {
            return false;
        }
        List<String> operations = share.getOperations();
        return operations != null
                && (operations.contains(operation.name()) || operations.contains(Operation.ALL.name()));
    }

    /**
     * True when one of the shares of the tenant grants the operation on this entity to the user or to a user group
     * the user belongs to. The shares are configured by the tenant administrator only (see ShareController).
     */
    private boolean isSharedWith(SecurityUser user, EntityId entityId, Operation operation) {
        if (user == null || user.getId() == null || entityId == null || entityId.getId() == null) {
            return false;
        }
        String assignee = user.getId().getId().toString();
        String entityType = entityId.getEntityType().name();
        String entity = entityId.getId().toString();
        Set<String> userGroupIds = null;
        for (RbacShare share : getShares(user.getTenantId())) {
            if (!entityType.equals(share.getEntityType()) || !entity.equals(share.getEntityId())
                    || !shareGrants(share, operation)) {
                continue;
            }
            if ("USER".equals(share.getAssigneeType())) {
                if (assignee.equals(share.getAssigneeId())) {
                    return true;
                }
            } else if ("USER_GROUP".equals(share.getAssigneeType())) {
                if (userGroupIds == null) {
                    userGroupIds = getUserGroupIds(user);
                }
                if (userGroupIds.contains(share.getAssigneeId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ids of the entities of the resource that are shared with the user with this operation, used to union the list
     * pages of a user with a role that does not cover the shared entities.
     */
    private Set<UUID> getSharedEntityIds(SecurityUser user, Resource resource, Operation operation) {
        Set<UUID> ids = new HashSet<>();
        if (user == null || user.getId() == null) {
            return ids;
        }
        String assignee = user.getId().getId().toString();
        String entityType = resource.name();
        Set<String> userGroupIds = null;
        for (RbacShare share : getShares(user.getTenantId())) {
            if (!entityType.equals(share.getEntityType()) || !shareGrants(share, operation)) {
                continue;
            }
            boolean forUser;
            if ("USER".equals(share.getAssigneeType())) {
                forUser = assignee.equals(share.getAssigneeId());
            } else if ("USER_GROUP".equals(share.getAssigneeType())) {
                if (userGroupIds == null) {
                    userGroupIds = getUserGroupIds(user);
                }
                forUser = userGroupIds.contains(share.getAssigneeId());
            } else {
                forUser = false;
            }
            if (forUser) {
                try {
                    ids.add(UUID.fromString(share.getEntityId()));
                } catch (IllegalArgumentException e) {
                    log.warn("[{}] Invalid entity id in the share {}: {}", user.getTenantId(), share.getId(),
                            share.getEntityId());
                }
            }
        }
        return ids;
    }

    /**
     * True when the entity belongs to the customer of the user or to any of its sub-customers.
     */
    private boolean userBelongsToCustomerSubtree(SecurityUser user, HasTenantId entity) {
        if (!isCustomerUser(user) || !(entity instanceof HasCustomerId)) {
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

    /**
     * Result of the role lookup: either the effective role of the user (null when the user has no custom role), or
     * the information that the RBAC settings of the tenant can not be read.
     */
    private record EffectiveRole(RbacRole role, boolean settingsUnavailable) {
        static final EffectiveRole NONE = new EffectiveRole(null, false);
    }

}
