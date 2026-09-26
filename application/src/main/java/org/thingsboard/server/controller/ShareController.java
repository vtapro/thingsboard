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
import org.thingsboard.server.common.data.rbac.RbacShare;
import org.thingsboard.server.common.data.rbac.RbacShareSettings;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.dao.settings.ShareService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.Set;
import java.util.UUID;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

/**
 * Shares of the tenant: an explicit grant of operations on one entity to a user or to a user group. Only the tenant
 * administrator may read or change them, and they are checked before the custom role of the user.
 */
@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class ShareController extends BaseController {

    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of("DEVICE", "ASSET", "ENTITY_VIEW", "DASHBOARD");
    private static final Set<String> ASSIGNEE_TYPES = Set.of("USER", "USER_GROUP");

    private final ShareService shareService;

    @ApiOperation(value = "Get the shares of the tenant (getShares)",
            notes = "Returns the entity shares configured for the tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/rbacShare")
    public RbacShareSettings getShares() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return shareService.getShareSettings(getCurrentUser().getTenantId());
    }

    @ApiOperation(value = "Create or update the shares of the tenant (saveShares)",
            notes = "Creates or updates the entity shares of the tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/rbacShare")
    public RbacShareSettings saveShares(
            @Parameter(description = "A JSON value representing the shares.") @RequestBody RbacShareSettings settings)
            throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        validate(settings);
        return shareService.saveShareSettings(getCurrentUser().getTenantId(), settings);
    }

    /**
     * A share is written by the administrator only, so the payload has to be complete and the operations have to be
     * known operations of the platform (no free text that would silently grant nothing or everything).
     */
    private void validate(RbacShareSettings settings) {
        if (settings == null || settings.getShares() == null) {
            return;
        }
        for (RbacShare share : settings.getShares()) {
            if (share.getEntityType() == null || !ALLOWED_ENTITY_TYPES.contains(share.getEntityType())) {
                throw new IncorrectParameterException("Unsupported entity type of the share: " + share.getEntityType()
                        + ", allowed: " + ALLOWED_ENTITY_TYPES);
            }
            if (share.getAssigneeType() == null || !ASSIGNEE_TYPES.contains(share.getAssigneeType())) {
                throw new IncorrectParameterException("Unsupported assignee type of the share: " + share.getAssigneeType()
                        + ", allowed: " + ASSIGNEE_TYPES);
            }
            validateId("entityId", share.getEntityId());
            validateId("assigneeId", share.getAssigneeId());
            if (share.getOperations() == null || share.getOperations().isEmpty()) {
                throw new IncorrectParameterException("The share of " + share.getEntityId() + " has no operation!");
            }
            for (String operation : share.getOperations()) {
                try {
                    Operation.valueOf(operation);
                } catch (IllegalArgumentException e) {
                    throw new IncorrectParameterException("Unknown operation of the share: " + operation);
                }
            }
        }
    }

    private void validateId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IncorrectParameterException("The " + name + " of the share is missing!");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IncorrectParameterException("The " + name + " of the share is not a valid id: " + value);
        }
    }

}
