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
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.rbac.EntityGroupMembers;
import org.thingsboard.server.common.data.rbac.EntityGroupMembersRequest;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.DefaultEntityGroupService;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.service.entitiy.TbLogEntityActionService;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@TbCoreComponent
public class EntityGroupController extends BaseController {

    private final EntityGroupService entityGroupService;
    private final TbLogEntityActionService logEntityActionService;

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
        RbacEntityGroupSettings saved = entityGroupService.saveEntityGroupSettings(getCurrentUser().getTenantId(), settings);
        accessControlService.onPermissionsChanged();
        return saved;
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
        RbacEntityGroupSettings saved = entityGroupService.saveEntityGroup(getCurrentUser().getTenantId(), entityGroup);
        accessControlService.onPermissionsChanged();
        return saved;
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
        RbacEntityGroupSettings saved = entityGroupService.deleteEntityGroup(getCurrentUser().getTenantId(), groupId);
        accessControlService.onPermissionsChanged();
        return saved;
    }

    @ApiOperation(value = "Get the entity groups of one entity (getEntityGroupMembers)",
            notes = "Returns the groups of the entity type of the entity and tells whether the entity is a member of "
                    + "each of them. Used by the \"Manage owner and groups\" dialog. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/api/tenant/entityGroup/members/{entityType}/{entityId}")
    public EntityGroupMembers getEntityGroupMembers(
            @Parameter(description = "Entity type of the entity, e.g. DEVICE or USER")
            @PathVariable("entityType") String entityType,
            @Parameter(description = "Id of the entity")
            @PathVariable("entityId") String entityId) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        EntityId id = checkEntity(entityType, entityId);
        syncDefaultUserGroups(id);
        return entityGroupMembers(id);
    }

    @ApiOperation(value = "Set the entity groups of one entity (saveEntityGroupMembers)",
            notes = "Adds the entity to the listed groups of its entity type and removes it from the other ones, "
                    + "without touching the membership of the other entities. Groups change what a user with a role "
                    + "scoped to those groups may read or change, therefore the caller also needs the WRITE "
                    + "permission on the entity itself. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/api/tenant/entityGroup/members/{entityType}/{entityId}")
    public EntityGroupMembers saveEntityGroupMembers(
            @Parameter(description = "Entity type of the entity, e.g. DEVICE or USER")
            @PathVariable("entityType") String entityType,
            @Parameter(description = "Id of the entity")
            @PathVariable("entityId") String entityId,
            @RequestBody EntityGroupMembersRequest request) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        EntityId id = checkEntity(entityType, entityId);
        Set<String> requested = request != null && request.getGroupIds() != null
                ? Set.copyOf(request.getGroupIds()) : Set.of();
        String target = id.getId().toString();
        RbacEntityGroupSettings settings = entityGroupService.getEntityGroupSettings(getCurrentUser().getTenantId());
        for (RbacEntityGroup group : settings.getGroups()) {
            if (!entityType.equals(group.getEntityType()) || group.isAllGroup()) {
                // the "All" group of the type contains every entity of the tenant, its membership is implicit
                continue;
            }
            if (group.getEntityIds() == null) {
                group.setEntityIds(new java.util.ArrayList<>());
            }
            if (requested.contains(group.getId())) {
                if (!group.getEntityIds().contains(target)) {
                    group.getEntityIds().add(target);
                }
            } else {
                group.getEntityIds().remove(target);
            }
        }
        entityGroupService.saveEntityGroupSettings(getCurrentUser().getTenantId(), settings);
        // the membership selects the roles of the user (and of the members of a user group), so the effective
        // permissions must be recomputed right away
        accessControlService.onPermissionsChanged();
        EntityGroupMembers members = entityGroupMembers(id);
        if (EntityType.USER == id.getEntityType()) {
            // changing the groups of a user changes its profile and its permissions: keep it in the audit log
            User member = userService.findUserById(getCurrentUser().getTenantId(), new UserId(id.getId()));
            if (member != null) {
                String groups = members.getGroups().stream()
                        .filter(EntityGroupMembers.Member::isMember)
                        .map(EntityGroupMembers.Member::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                logEntityActionService.logEntityAction(getCurrentUser().getTenantId(), member.getId(), member,
                        member.getCustomerId(), ActionType.UPDATED, getCurrentUser(), "groups: " + groups);
            }
        }
        return members;
    }

    /**
     * Validates the entity type of the group and that the caller may write the entity itself (RBAC scope).
     */
    private EntityId checkEntity(String entityType, String entityId) throws ThingsboardException {
        validateEntityType(entityType);
        if (StringUtils.isBlank(entityId)) {
            throw new IncorrectParameterException("Entity id is required");
        }
        EntityId id = EntityIdFactory.getByTypeAndUuid(entityType, UUID.fromString(entityId));
        checkEntityId(id, Operation.WRITE);
        return id;
    }

    /**
     * The default user groups follow the authority of the user, so the dialog always shows the groups a tenant
     * administrator or a customer user belongs to, even when the user existed before the groups were created.
     */
    private void syncDefaultUserGroups(EntityId entityId) throws ThingsboardException {
        if (EntityType.USER == entityId.getEntityType()) {
            User user = checkUserId(new UserId(entityId.getId()), Operation.READ);
            entityGroupService.syncUserGroups(getCurrentUser().getTenantId(), user);
        }
    }

    private EntityGroupMembers entityGroupMembers(EntityId entityId) throws ThingsboardException {
        RbacEntityGroupSettings settings = entityGroupService.getEntityGroupSettings(getCurrentUser().getTenantId());
        String target = entityId.getId().toString();
        List<EntityGroupMembers.Member> members = new java.util.ArrayList<>();
        for (RbacEntityGroup group : settings.getGroups()) {
            if (!group.getEntityType().equals(entityId.getEntityType().name())) {
                continue;
            }
            boolean member = group.isAllGroup()
                    || (group.getEntityIds() != null && group.getEntityIds().contains(target));
            members.add(new EntityGroupMembers.Member(group.getId(), group.getName(), group.getDescription(),
                    group.isPublicGroup(), group.isAllGroup(), group.isAllGroup(), member));
        }
        return new EntityGroupMembers(entityId, members);
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
