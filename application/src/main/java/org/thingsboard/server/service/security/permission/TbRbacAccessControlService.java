// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.dao.settings.UserGroupService;
import org.thingsboard.server.dao.settings.CustomerHierarchyService;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacUserGroup;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.thingsboard.server.common.data.security.Authority.SYS_ADMIN;

/**
 * Custom RBAC enforcement, disabled by default.
 *
 * When {@code security.rbac.enabled=true}:
 *   - user is assigned to one of the tenant custom roles -> permissions of that role are enforced;
 *   - user has no custom role (or anything fails) -> the platform default permission matrix is used.
 *
 * When the flag is off this bean is not created at all, so the platform behaviour is unchanged.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(value = "security.rbac.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TbRbacAccessControlService implements AccessControlService {

    private static final String PERMISSION_DENIED_MESSAGE = "You don't have permission to perform this operation!";

    private final DefaultAccessControlService defaultAccessControlService;
    private final RoleService roleService;
    private final EntityGroupService entityGroupService;
    private final UserGroupService userGroupService;
    private final CustomerHierarchyService customerHierarchyService;

    @Override
    public boolean hasPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        RbacRole role = getEffectiveRole(user);
        if (role == null) {
            return defaultAccessControlService.hasPermission(user, resource, operation);
        }
        return hasGlobalOperation(role, resource, operation);
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
        if (role == null) {
            return defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity);
        }
        if (role.isOwnCustomerOnly()) {
            return userBelongsToCustomerSubtree(user, entity);
        }
        if (hasGlobalOperation(role, resource, operation)) {
            return true;
        }
        List<String> scopedGroups = getScopedGroups(role, resource, operation);
        if (scopedGroups == null || scopedGroups.isEmpty()) {
            return hasCustomerHierarchyAccess(user, entity, role, resource, operation);
        }
        return entityBelongsToGroups(user.getTenantId(), entityId, scopedGroups)
                || hasCustomerHierarchyAccess(user, entity, role, resource, operation);
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> void checkPermission(SecurityUser user, Resource resource, Operation operation,
                                                                            I entityId, T entity) throws ThingsboardException {
        if (!hasPermission(user, resource, operation, entityId, entity)) {
            permissionDenied();
        }
    }

    private RbacRole getEffectiveRole(SecurityUser user) {
        if (user == null || user.getId() == null || user.getTenantId() == null || SYS_ADMIN.equals(user.getAuthority())) {
            return null;
        }
        try {
            String userId = user.getId().getId().toString();
            List<RbacRole> roles = roleService.getRoleSettings(user.getTenantId()).getRoles();
            Set<String> roleIds = new HashSet<>();
            for (RbacRole role : roles) {
                if (role.getUserIds() != null && role.getUserIds().contains(userId)) {
                    roleIds.add(role.getId());
                }
            }
            for (RbacUserGroup group : userGroupService.getUserGroupSettings(user.getTenantId()).getGroups()) {
                if (group.getRoleIds() != null && group.getUserIds() != null && group.getUserIds().contains(userId)) {
                    roleIds.addAll(group.getRoleIds());
                }
            }
            if (roleIds.isEmpty()) {
                return null;
            }
            RbacRole effective = new RbacRole();
            effective.setId("effective");
            effective.setName("effective");
            for (RbacRole role : roles) {
                if (roleIds.contains(role.getId())) {
                    mergePermissions(role, effective);
                }
            }
            return effective;
        } catch (Exception e) {
            log.warn("Failed to load RBAC roles for user [{}], falling back to default permissions: {}",
                    user.getId(), e.getMessage());
        }
        return null;
    }

    private void mergePermissions(RbacRole source, RbacRole target) {
        if (source.getPermissions() != null) {
            source.getPermissions().forEach((resource, operations) -> {
                List<String> merged = target.getPermissions().computeIfAbsent(resource, r -> new java.util.ArrayList<>());
                for (String operation : operations) {
                    if (!merged.contains(operation)) {
                        merged.add(operation);
                    }
                }
            });
        }
        if (source.getScopedPermissions() != null) {
            source.getScopedPermissions().forEach((resource, byOperation) ->
                byOperation.forEach((operation, groupIds) -> {
                    List<String> merged = target.getScopedPermissions()
                        .computeIfAbsent(resource, r -> new java.util.HashMap<>())
                        .computeIfAbsent(operation, o -> new java.util.ArrayList<>());
                    for (String groupId : groupIds) {
                        if (!merged.contains(groupId)) {
                            merged.add(groupId);
                        }
                    }
                }));
        }
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
            for (RbacEntityGroup group : entityGroupService.getEntityGroupSettings(tenantId).getGroups()) {
                if (groupIds.contains(group.getId()) && group.getEntityIds() != null && group.getEntityIds().contains(id)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve entity groups for entity [{}]: {}", entityId, e.getMessage());
        }
        return false;
    }

    /**
     * A customer user with a role granting the operation may access entities that belong to its sub-customers
     * (customer hierarchy configured by the tenant administrator).
     */
    private boolean hasCustomerHierarchyAccess(SecurityUser user, HasTenantId entity,
                                               RbacRole role, Resource resource, Operation operation) {
        if (user.getCustomerId() == null || !(entity instanceof HasCustomerId)) {
            return false;
        }
        if (!hasGlobalOperation(role, resource, operation)) {
            return false;
        }
        return userBelongsToCustomerSubtree(user, entity);
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
            Set<String> subtree = customerHierarchyService.getCustomerSubtree(user.getTenantId(),
                    user.getCustomerId().getId().toString());
            return subtree.contains(entityCustomerId.getId().toString());
        } catch (Exception e) {
            log.warn("Failed to resolve customer subtree for user [{}]: {}", user.getId(), e.getMessage());
            return false;
        }
    }

    private void permissionDenied() throws ThingsboardException {
        throw new ThingsboardException(PERMISSION_DENIED_MESSAGE,
                ThingsboardErrorCode.PERMISSION_DENIED);
    }

}
