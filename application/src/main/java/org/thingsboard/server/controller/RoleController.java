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
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

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

}
