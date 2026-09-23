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
import org.thingsboard.server.common.data.rbac.RbacCustomerHierarchy;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.CustomerHierarchyService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class CustomerHierarchyController extends BaseController {

    private final CustomerHierarchyService customerHierarchyService;

    @ApiOperation(value = "Get customer hierarchy of the current tenant (getCustomerHierarchy)",
            notes = "Returns the parent relation of the tenant customers. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/customerHierarchy")
    public RbacCustomerHierarchy getCustomerHierarchy() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return customerHierarchyService.getCustomerHierarchy(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create Or Update customer hierarchy (saveCustomerHierarchy)",
            notes = "Creates or updates the parent relations of the tenant customers. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/customerHierarchy")
    public RbacCustomerHierarchy saveCustomerHierarchy(
            @Parameter(description = "A JSON value representing the customer hierarchy.")
            @RequestBody RbacCustomerHierarchy hierarchy) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return customerHierarchyService.saveCustomerHierarchy(getCurrentUser().getTenantId(), hierarchy);
    }

}
