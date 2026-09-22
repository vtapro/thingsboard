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
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.mail.MailTemplateSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.MailTemplateService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.SYSTEM_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class MailTemplateController extends BaseController {

    private final MailTemplateService mailTemplateService;

    @ApiOperation(value = "Get the Mail Template Settings of the current tenant (getMailTemplateSettings)",
            notes = "Returns the mail template settings of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/mailTemplates")
    public MailTemplateSettings getMailTemplateSettings() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return mailTemplateService.getMailTemplateSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update the Mail Template Settings of the current tenant (saveMailTemplateSettings)",
            notes = "Creates or updates the mail template settings of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/mailTemplates")
    public MailTemplateSettings saveMailTemplateSettings(
            @Parameter(description = "A JSON value representing the mail template settings.")
            @RequestBody MailTemplateSettings mailTemplateSettings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return mailTemplateService.saveMailTemplateSettings(getCurrentUser().getTenantId(), mailTemplateSettings);
    }

    @ApiOperation(value = "Get the system Mail Template Settings (getSystemMailTemplateSettings)",
            notes = "Returns the system mail template settings. " + SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @GetMapping(value = "/api/admin/mailTemplates")
    public MailTemplateSettings getSystemMailTemplateSettings() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return mailTemplateService.getMailTemplateSettings(TenantId.SYS_TENANT_ID);
    }

    @ApiOperation(value = "Create Or Update the system Mail Template Settings (saveSystemMailTemplateSettings)",
            notes = "Creates or updates the system mail template settings. " + SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @PostMapping(value = "/api/admin/mailTemplates")
    public MailTemplateSettings saveSystemMailTemplateSettings(
            @Parameter(description = "A JSON value representing the mail template settings.")
            @RequestBody MailTemplateSettings mailTemplateSettings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return mailTemplateService.saveMailTemplateSettings(TenantId.SYS_TENANT_ID, mailTemplateSettings);
    }

}
