// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.whiteLabeling.WhiteLabelingSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.WhiteLabelingService;
import org.thingsboard.server.dao.domain.DomainService;
import org.thingsboard.server.common.data.domain.Domain;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.SYSTEM_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class WhiteLabelingController extends BaseController {

    private final WhiteLabelingService whiteLabelingService;
    private final DomainService domainService;

    @ApiOperation(value = "Get the White Labeling Settings (getWhiteLabelingSettings)",
            notes = "Returns the branding settings that are safe to expose to unauthenticated users, " +
                    "so that the login page and the application shell may be branded accordingly.")
    @GetMapping(value = "/api/noauth/whiteLabeling")
    public WhiteLabelingSettings getWhiteLabelingSettings(HttpServletRequest request) {
        String host = request.getServerName();
        if (host != null && !host.isBlank()) {
            Domain domain = domainService.findDomainByName(host);
            if (domain != null && domain.getTenantId() != null) {
                return whiteLabelingService.getWhiteLabelingSettings(domain.getTenantId());
            }
        }
        return whiteLabelingService.getWhiteLabelingSettings();
    }

    @ApiOperation(value = "Get the White Labeling Settings (getAdminWhiteLabelingSettings)",
            notes = "Returns the branding settings of the platform. " + SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @GetMapping(value = "/api/admin/whiteLabeling")
    public WhiteLabelingSettings getAdminWhiteLabelingSettings() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return whiteLabelingService.getWhiteLabelingSettings();
    }

    @ApiOperation(value = "Get the White Labeling Settings for the current tenant (getTenantWhiteLabelingSettings)",
            notes = "Returns the branding settings of the current tenant, falling back to the system branding settings " +
                    "when the tenant has no custom settings.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/api/whiteLabeling")
    public WhiteLabelingSettings getTenantWhiteLabelingSettings() throws ThingsboardException {
        return whiteLabelingService.getWhiteLabelingSettings(getTenantId());
    }

    @ApiOperation(value = "Create Or Update the White Labeling Settings of the current tenant (saveTenantWhiteLabelingSettings)",
            notes = "Creates or updates the branding settings of the current tenant. " +
                    "The tenant branding overrides the system branding for the tenant users.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/whiteLabeling")
    public WhiteLabelingSettings saveTenantWhiteLabelingSettings(
            @Parameter(description = "A JSON value representing the white labeling settings.")
            @RequestBody WhiteLabelingSettings whiteLabelingSettings) throws ThingsboardException {
        return whiteLabelingService.saveWhiteLabelingSettings(getCurrentUser().getTenantId(), whiteLabelingSettings);
    }

    @ApiOperation(value = "Create Or Update the White Labeling Settings (saveWhiteLabelingSettings)",
            notes = "Creates or updates the branding settings of the platform. " + SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @PostMapping(value = "/api/admin/whiteLabeling")
    public WhiteLabelingSettings saveWhiteLabelingSettings(
            @Parameter(description = "A JSON value representing the white labeling settings.")
            @RequestBody WhiteLabelingSettings whiteLabelingSettings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return whiteLabelingService.saveWhiteLabelingSettings(whiteLabelingSettings);
    }

}
