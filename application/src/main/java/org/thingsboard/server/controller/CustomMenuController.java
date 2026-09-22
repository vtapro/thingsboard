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
import org.thingsboard.server.common.data.menu.CustomMenuSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.CustomMenuService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class CustomMenuController extends BaseController {

    private final CustomMenuService customMenuService;

    @ApiOperation(value = "Get custom menu of the current tenant (getCustomMenu)",
            notes = "Returns custom menu items configured for the current tenant. " +
                    "Available to any authenticated user, so the application can build the menu.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/api/customMenu")
    public CustomMenuSettings getCustomMenu() throws ThingsboardException {
        return customMenuService.getCustomMenuSettings(getTenantId());
    }

    @ApiOperation(value = "Get custom menu settings (getCustomMenuSettings)",
            notes = "Returns custom menu items configured for the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/customMenu")
    public CustomMenuSettings getCustomMenuSettings() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return customMenuService.getCustomMenuSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update custom menu (saveCustomMenu)",
            notes = "Creates or updates custom menu items of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/customMenu")
    public CustomMenuSettings saveCustomMenu(
            @Parameter(description = "A JSON value representing the custom menu settings.")
            @RequestBody CustomMenuSettings customMenuSettings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return customMenuService.saveCustomMenuSettings(getCurrentUser().getTenantId(), customMenuSettings);
    }

}
