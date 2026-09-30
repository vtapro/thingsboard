// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

import java.util.List;

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
        validateSettings(getCurrentUser().getTenantId(), settings);
        return entityGroupService.saveEntityGroupSettings(getCurrentUser().getTenantId(), settings);
    }

    @ApiOperation(value = "Create Or Update a single entity group (saveEntityGroup)",
            notes = "Creates or updates one entity group, including its members, without touching the other " +
                    "groups of the tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/entityGroup/group")
    public RbacEntityGroupSettings saveEntityGroup(
            @Parameter(description = "A JSON value representing the entity group.")
            @RequestBody RbacEntityGroup entityGroup) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        if (entityGroup == null || StringUtils.isBlank(entityGroup.getId())) {
            throw new IncorrectParameterException("Entity group id is required");
        }
        if (StringUtils.isBlank(entityGroup.getName())) {
            throw new IncorrectParameterException("Entity group name is required");
        }
        validateEntityType(entityGroup.getEntityType());
        if (isAllGroup(getCurrentUser().getTenantId(), entityGroup.getId())) {
            throw new IncorrectParameterException("The All group of an entity type contains every entity of the tenant "
                    + "and can not be modified");
        }
        return entityGroupService.saveEntityGroup(getCurrentUser().getTenantId(), entityGroup);
    }

    @ApiOperation(value = "Delete a single entity group (deleteEntityGroup)",
            notes = "Deletes one entity group, without touching the other groups of the tenant. " +
                    TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/api/tenant/entityGroup/{groupId}")
    public RbacEntityGroupSettings deleteEntityGroup(
            @Parameter(description = "Id of the entity group")
            @PathVariable("groupId") String groupId) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        if (StringUtils.isBlank(groupId)) {
            throw new IncorrectParameterException("Entity group id is required");
        }
        if (isAllGroup(getCurrentUser().getTenantId(), groupId)) {
            throw new IncorrectParameterException("The All group of an entity type contains every entity of the tenant "
                    + "and can not be deleted");
        }
        return entityGroupService.deleteEntityGroup(getCurrentUser().getTenantId(), groupId);
    }

    /** True when the group is the "All" group of its entity type (created by the backend, read only). */
    private boolean isAllGroup(TenantId tenantId, String groupId) {
        RbacEntityGroupSettings settings = entityGroupService.getEntityGroupSettings(tenantId);
        return settings.getGroups() != null && settings.getGroups().stream()
                .anyMatch(group -> groupId.equals(group.getId()) && group.isAllGroup());
    }

    /**
     * The "All" groups of the entity types are created by the backend and always match every entity of the tenant, so
     * the payload may not add, remove or change them.
     */
    private void validateSettings(TenantId tenantId, RbacEntityGroupSettings settings) {
        List<RbacEntityGroup> groups = settings != null ? settings.getGroups() : null;
        if (groups == null) {
            return;
        }
        RbacEntityGroupSettings stored = entityGroupService.getEntityGroupSettings(tenantId);
        List<RbacEntityGroup> storedGroups = stored.getGroups() != null ? stored.getGroups() : List.of();
        for (RbacEntityGroup group : groups) {
            if (group == null) {
                throw new IncorrectParameterException("Entity group must not be null");
            }
            if (StringUtils.isBlank(group.getId())) {
                throw new IncorrectParameterException("Entity group id is required");
            }
            if (StringUtils.isBlank(group.getName())) {
                throw new IncorrectParameterException("Entity group name is required");
            }
            validateEntityType(group.getEntityType());
            if (group.isAllGroup() && storedGroups.stream()
                    .noneMatch(storedGroup -> group.getId().equals(storedGroup.getId()) && storedGroup.isAllGroup())) {
                throw new IncorrectParameterException("Only the backend may create the All group of an entity type");
            }
        }
        for (RbacEntityGroup storedGroup : storedGroups) {
            if (!storedGroup.isAllGroup()) {
                continue;
            }
            boolean kept = groups.stream().anyMatch(group -> storedGroup.getId().equals(group.getId())
                    && group.isAllGroup() && storedGroup.getEntityType().equals(group.getEntityType()));
            if (!kept) {
                throw new IncorrectParameterException("The All group of " + storedGroup.getEntityType()
                        + " contains every entity of the tenant and can not be removed or changed");
            }
        }
    }

    private static void validateEntityType(String entityType) {
        if (StringUtils.isBlank(entityType) || !ALLOWED_ENTITY_TYPES.contains(entityType)) {
            throw new IncorrectParameterException("Entity group type must be one of " + ALLOWED_ENTITY_TYPES);
        }
    }

    /**
     * Entity types that may be grouped: the classic PE ones plus the members of this fork (customers and users),
     * for which the platform also creates an "All" group.
     */
    private static final List<String> ALLOWED_ENTITY_TYPES =
            List.of("DEVICE", "ASSET", "ENTITY_VIEW", "CUSTOMER", "USER");

}
