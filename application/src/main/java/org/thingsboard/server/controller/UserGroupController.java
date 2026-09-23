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
import org.thingsboard.server.common.data.rbac.RbacUserGroupSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.UserGroupService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class UserGroupController extends BaseController {

    private final UserGroupService userGroupService;

    @ApiOperation(value = "Get user groups of the current tenant (getUserGroups)",
            notes = "Returns user groups configured for the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/userGroup")
    public RbacUserGroupSettings getUserGroups() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return userGroupService.getUserGroupSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update user groups (saveUserGroups)",
            notes = "Creates or updates user groups of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/userGroup")
    public RbacUserGroupSettings saveUserGroups(
            @Parameter(description = "A JSON value representing the user groups.")
            @RequestBody RbacUserGroupSettings settings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return userGroupService.saveUserGroupSettings(getCurrentUser().getTenantId(), settings);
    }

}
