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
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.menu.CustomMenuSettings;
import org.thingsboard.server.common.data.menu.CustomMenuItem;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.CustomMenuService;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.Locale;
import java.util.UUID;

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
        validateCustomMenu(customMenuSettings);
        return customMenuService.saveCustomMenuSettings(getCurrentUser().getTenantId(), customMenuSettings);
    }

    /**
     * The custom menu items are rendered by the browser, therefore only the dashboard links and the http(s) urls
     * are accepted. This blocks {@code javascript:} and other unsafe url schemes.
     */
    private void validateCustomMenu(CustomMenuSettings settings) {
        if (settings == null || settings.getItems() == null) {
            return;
        }
        for (CustomMenuItem item : settings.getItems()) {
            if (StringUtils.isBlank(item.getName()) || StringUtils.isBlank(item.getTarget())) {
                throw new IncorrectParameterException("Custom menu item name and target are required");
            }
            if (!"dashboard".equals(item.getType()) && !"url".equals(item.getType())) {
                throw new IncorrectParameterException("Custom menu item type must be 'dashboard' or 'url'");
            }
            if ("dashboard".equals(item.getType())) {
                try {
                    UUID.fromString(item.getTarget().trim());
                } catch (IllegalArgumentException e) {
                    throw new IncorrectParameterException("Custom menu item of type 'dashboard' must reference a dashboard id");
                }
            }
            if ("url".equals(item.getType()) && !isSafeUrl(item.getTarget())) {
                throw new IncorrectParameterException("Custom menu item url must use the http or https scheme");
            }
        }
    }

    private boolean isSafeUrl(String target) {
        String url = target.trim().toLowerCase(Locale.ROOT);
        return url.startsWith("https://") || url.startsWith("http://");
    }

}
