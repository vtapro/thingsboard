// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.CustomTranslationService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.Map;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class CustomTranslationController extends BaseController {

    private final CustomTranslationService customTranslationService;

    @ApiOperation(value = "Get custom translations of the current tenant (getCustomTranslations)",
            notes = "Returns custom translations of the current tenant grouped by locale. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/customTranslation")
    public Map<String, Map<String, String>> getCustomTranslations() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return customTranslationService.getCustomTranslations(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Get custom translations for the locale (getCustomTranslationsForLocale)",
            notes = "Returns custom translations of the current tenant for the given locale.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/api/customTranslation/{locale}")
    public Map<String, String> getCustomTranslationsForLocale(
            @Parameter(description = "Locale code, for example en_US")
            @PathVariable("locale") String locale) throws ThingsboardException {
        return customTranslationService.getCustomTranslations(getTenantId(), locale);
    }

    @ApiOperation(value = "Create Or Update custom translations for the locale (saveCustomTranslations)",
            notes = "Creates or updates custom translations of the current tenant for the given locale. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/customTranslation/{locale}")
    public Map<String, String> saveCustomTranslations(
            @Parameter(description = "Locale code, for example en_US")
            @PathVariable("locale") String locale,
            @Parameter(description = "A JSON object with translation keys and values.")
            @RequestBody Map<String, String> translations) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return customTranslationService.saveCustomTranslations(getCurrentUser().getTenantId(), locale, translations);
    }

    @ApiOperation(value = "Delete custom translations for the locale (deleteCustomTranslations)",
            notes = "Removes custom translations of the current tenant for the given locale. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/api/tenant/customTranslation/{locale}")
    public Map<String, Map<String, String>> deleteCustomTranslations(
            @Parameter(description = "Locale code, for example en_US")
            @PathVariable("locale") String locale) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return customTranslationService.deleteCustomTranslations(getCurrentUser().getTenantId(), locale);
    }

}
