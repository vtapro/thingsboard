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
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.Map;

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

    @Override
    public boolean hasPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException {
        Map<String, List<String>> permissions = getRbacPermissions(user);
        if (permissions == null) {
            return defaultAccessControlService.hasPermission(user, resource, operation);
        }
        List<String> operations = permissions.get(resource.name());
        return operations != null && operations.contains(operation.name());
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
        Map<String, List<String>> permissions = getRbacPermissions(user);
        if (permissions == null) {
            return defaultAccessControlService.hasPermission(user, resource, operation, entityId, entity);
        }
        List<String> operations = permissions.get(resource.name());
        return operations != null && operations.contains(operation.name());
    }

    @Override
    public <I extends EntityId, T extends HasTenantId> void checkPermission(SecurityUser user, Resource resource, Operation operation,
                                                                            I entityId, T entity) throws ThingsboardException {
        if (!hasPermission(user, resource, operation, entityId, entity)) {
            permissionDenied();
        }
    }

    private Map<String, List<String>> getRbacPermissions(SecurityUser user) {
        if (user == null || user.getId() == null || user.getTenantId() == null || SYS_ADMIN.equals(user.getAuthority())) {
            return null;
        }
        try {
            String userId = user.getId().getId().toString();
            for (RbacRole role : roleService.getRoleSettings(user.getTenantId()).getRoles()) {
                if (role.getUserIds() != null && role.getUserIds().contains(userId)) {
                    return role.getPermissions();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load RBAC roles for user [{}], falling back to default permissions: {}",
                    user.getId(), e.getMessage());
        }
        return null;
    }

    private void permissionDenied() throws ThingsboardException {
        throw new ThingsboardException(PERMISSION_DENIED_MESSAGE,
                ThingsboardErrorCode.PERMISSION_DENIED);
    }

}
