// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.service.security.permission.TbRbacAccessControlService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

import java.util.List;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class RoleController extends BaseController {

    private final RoleService roleService;

    @ApiOperation(value = "Get roles of the current tenant (getRoles)",
            notes = "Returns custom roles configured for the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/role")
    public RbacRoleSettings getRoles() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return roleService.getRoleSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update roles (saveRoles)",
            notes = "Creates or updates custom roles of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/role")
    public RbacRoleSettings saveRoles(
            @Parameter(description = "A JSON value representing the roles.")
            @RequestBody RbacRoleSettings settings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        validateScopedPermissions(settings);
        return roleService.saveRoleSettings(getCurrentUser().getTenantId(), settings);
    }

    /**
     * Permissions can be scoped to entity groups only for the entities that may belong to a group (devices, assets
     * and entity views). Everything else has to be granted globally, otherwise the scope would always be empty.
     */
    private void validateScopedPermissions(RbacRoleSettings settings) {
        if (settings == null || settings.getRoles() == null) {
            return;
        }
        for (RbacRole role : settings.getRoles()) {
            if (role.getScopedPermissions() == null) {
                continue;
            }
            for (String resource : role.getScopedPermissions().keySet()) {
                if (!TbRbacAccessControlService.GROUP_SCOPED_RESOURCES.contains(resource)) {
                    throw new IncorrectParameterException("Permissions of " + resource
                            + " can not be scoped to entity groups, allowed resources: "
                            + TbRbacAccessControlService.GROUP_SCOPED_RESOURCES);
                }
            }
        }
    }

    @ApiOperation(value = "Get roles assigned to the current user (getCurrentUserRoles)",
            notes = "Returns the custom RBAC roles that apply to the current user, including the roles the user " +
                    "inherits from its user groups. Available to any authenticated user, so the application can " +
                    "build the menu according to the user permissions.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/api/user/roles")
    public List<RbacRole> getCurrentUserRoles() throws ThingsboardException {
        String userId = getCurrentUser().getId().getId().toString();
        return roleService.getEffectiveRoles(getCurrentUser().getTenantId(), userId);
    }

}
