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
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

import java.util.ArrayList;
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
        return roleService.saveRoleSettings(getCurrentUser().getTenantId(), settings);
    }

    @ApiOperation(value = "Get roles assigned to the current user (getCurrentUserRoles)",
            notes = "Returns custom RBAC roles assigned to the current user. Available to any authenticated user, " +
                    "so the application can build the menu according to the user permissions.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/api/user/roles")
    public List<RbacRole> getCurrentUserRoles() throws ThingsboardException {
        String userId = getCurrentUser().getId().getId().toString();
        List<RbacRole> result = new ArrayList<>();
        for (RbacRole role : roleService.getRoleSettings(getCurrentUser().getTenantId()).getRoles()) {
            if (role.getUserIds() != null && role.getUserIds().contains(userId)) {
                result.add(role);
            }
        }
        return result;
    }

}
