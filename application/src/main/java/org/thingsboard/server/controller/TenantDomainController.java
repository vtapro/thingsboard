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
import org.thingsboard.server.common.data.domain.Domain;
import org.thingsboard.server.common.data.domain.DomainInfo;
import org.thingsboard.server.common.data.id.DomainId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.domain.DomainService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.domain.TbDomainService;

import java.util.Collections;
import java.util.UUID;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class TenantDomainController extends BaseController {

    private static final int DEFAULT_PAGE_SIZE = 100;

    private final DomainService domainService;
    private final TbDomainService tbDomainService;

    @ApiOperation(value = "Get domains of the current tenant (getTenantDomains)",
            notes = "Returns domains registered for the current tenant. Domains are used to resolve branding " +
                    "on the login page. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/domain")
    public PageData<DomainInfo> getTenantDomains() throws Exception {
        return domainService.findDomainInfosByTenantId(getCurrentUser().getTenantId(), new PageLink(DEFAULT_PAGE_SIZE));
    }

    @ApiOperation(value = "Create Or Update domain of the current tenant (saveTenantDomain)",
            notes = "Creates or updates a domain of the current tenant. White labeling of the tenant is applied " +
                    "when the platform is accessed using this domain. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/domain")
    public Domain saveTenantDomain(
            @Parameter(description = "A JSON value representing the domain.")
            @RequestBody Domain domain) throws Exception {
        domain.setTenantId(getCurrentUser().getTenantId());
        return tbDomainService.save(domain, Collections.emptyList(), getCurrentUser());
    }

    @ApiOperation(value = "Delete domain of the current tenant (deleteTenantDomain)",
            notes = "Deletes a domain of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/api/tenant/domain/{id}")
    public void deleteTenantDomain(
            @Parameter(description = "Domain id")
            @PathVariable("id") UUID id) throws Exception {
        Domain domain = domainService.findDomainById(getCurrentUser().getTenantId(), new DomainId(id));
        if (domain != null && domain.getTenantId().equals(getCurrentUser().getTenantId())) {
            tbDomainService.delete(domain, getCurrentUser());
        }
    }

}
