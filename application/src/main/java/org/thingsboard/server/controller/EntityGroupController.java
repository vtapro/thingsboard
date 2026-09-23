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
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class EntityGroupController extends BaseController {

    private final EntityGroupService entityGroupService;

    @ApiOperation(value = "Get entity groups of the current tenant (getEntityGroups)",
            notes = "Returns entity groups (devices, assets, entity views) configured for the current tenant. " +
                    TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/entityGroup")
    public RbacEntityGroupSettings getEntityGroups() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return entityGroupService.getEntityGroupSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update entity groups (saveEntityGroups)",
            notes = "Creates or updates entity groups of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/entityGroup")
    public RbacEntityGroupSettings saveEntityGroups(
            @Parameter(description = "A JSON value representing the entity groups.")
            @RequestBody RbacEntityGroupSettings settings) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return entityGroupService.saveEntityGroupSettings(getCurrentUser().getTenantId(), settings);
    }

}
