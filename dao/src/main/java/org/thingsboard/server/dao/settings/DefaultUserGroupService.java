// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacUserGroup;
import org.thingsboard.server.common.data.rbac.RbacUserGroupSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultUserGroupService implements UserGroupService {

    public static final String USER_GROUPS_SETTINGS_KEY = "userGroups";

    private final AdminSettingsService adminSettingsService;
    private final EntityGroupService entityGroupService;
    private final TenantSettingsLocks locks = new TenantSettingsLocks();

    @Override
    public RbacUserGroupSettings getUserGroupSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, USER_GROUPS_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return ensureDefaultUserGroups(tenantId, new RbacUserGroupSettings());
        }
        try {
            return ensureDefaultUserGroups(tenantId, JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER
                    .convertValue(adminSettings.getJsonValue(), RbacUserGroupSettings.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load user group settings!", e);
        }
    }

    /**
     * Every entity group of the user type (the list of the "Manage owner and groups" dialog) has a matching user group,
     * so the tenant administrator may grant roles to it in Security → Roles: the group is the same object on both
     * sides — the dialog edits the members, the Roles page assigns the roles. The two profiles of a customer user keep
     * their pre-configured role, and the members of every group follow the dialog (the single place that edits them).
     *
     * <p>A user group created by the tenant administrator in the Roles page is mirrored into an entity group when it
     * is saved (see {@link #saveUserGroupSettings}).
     */
    private RbacUserGroupSettings ensureDefaultUserGroups(TenantId tenantId, RbacUserGroupSettings settings) {
        if (settings.getGroups() == null) {
            settings.setGroups(new ArrayList<>());
        }
        List<RbacEntityGroup> entityGroups = entityGroupService.getEntityGroupSettings(tenantId).getGroups();
        Set<String> entityGroupNames = new LinkedHashSet<>();
        for (RbacEntityGroup entityGroup : entityGroups) {
            if (!"USER".equals(entityGroup.getEntityType()) || entityGroup.isAllGroup()
                    || entityGroup.getName() == null || entityGroup.getName().isBlank()) {
                continue;
            }
            String name = entityGroup.getName();
            entityGroupNames.add(name);
            String groupId = DefaultEntityGroupService.defaultUserGroupId(tenantId, name);
            RbacUserGroup group = settings.getGroups().stream()
                    .filter(stored -> groupId.equals(stored.getId()) || name.equals(stored.getName()))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new RbacUserGroup();
                group.setId(groupId);
                group.setName(name);
                settings.getGroups().add(group);
            }
            if (group.getRoleIds() == null) {
                group.setRoleIds(new ArrayList<>());
            }
            // the two profiles of a customer user always carry their pre-configured role
            if (DefaultEntityGroupService.CUSTOMER_PROFILE_GROUP_NAMES.contains(name)) {
                String roleName = DefaultEntityGroupService.CUSTOMER_ADMINS_GROUP_NAME.equals(name)
                        ? DefaultRoleService.CUSTOMER_ADMIN_ROLE_NAME : DefaultRoleService.CUSTOMER_USER_ROLE_NAME;
                String roleId = DefaultRoleService.defaultRoleId(tenantId, roleName);
                if (!group.getRoleIds().contains(roleId)) {
                    group.getRoleIds().add(roleId);
                }
            }
            // the members follow the entity group: the dialog is the place that edits them
            group.setUserIds(new ArrayList<>(entityGroup.getEntityIds() != null
                    ? entityGroup.getEntityIds() : List.of()));
        }
        // a group that only exists in the settings any more (the administrator removed it in the dialog) goes away
        settings.getGroups().removeIf(group -> group.getName() != null && !group.getName().isBlank()
                && DefaultEntityGroupService.defaultUserGroupId(tenantId, group.getName()).equals(group.getId())
                && !entityGroupNames.contains(group.getName()));
        return settings;
    }

    @Override
    public RbacUserGroupSettings saveUserGroupSettings(TenantId tenantId, RbacUserGroupSettings settings) {
        synchronized (locks.lockFor(tenantId)) {
            AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, USER_GROUPS_SETTINGS_KEY);
            if (adminSettings == null) {
                adminSettings = new AdminSettings();
                adminSettings.setTenantId(tenantId);
                adminSettings.setKey(USER_GROUPS_SETTINGS_KEY);
            }
            adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
            AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
            RbacUserGroupSettings savedSettings = JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER
                    .convertValue(saved.getJsonValue(), RbacUserGroupSettings.class);
            // the group is offered by the "Manage owner and groups" dialog as well, so mirror it into an entity group
            // of the user type (the dialog then edits its members, which the next read copies back here)
            for (RbacUserGroup group : savedSettings.getGroups() != null ? savedSettings.getGroups() : List.<RbacUserGroup>of()) {
                if (group.getName() == null || group.getName().isBlank()) {
                    continue;
                }
                RbacEntityGroup entityGroup = entityGroupService.getEntityGroupSettings(tenantId).getGroups().stream()
                        .filter(candidate -> "USER".equals(candidate.getEntityType())
                                && group.getName().equals(candidate.getName()))
                        .findFirst().orElse(null);
                if (entityGroup == null) {
                    entityGroup = new RbacEntityGroup();
                    entityGroup.setId(DefaultEntityGroupService.defaultUserGroupId(tenantId, group.getName()));
                    entityGroup.setName(group.getName());
                    entityGroup.setEntityType("USER");
                    entityGroup.setDescription("Users of the group " + group.getName()
                            + " (assign roles in Security -> Roles, edit the members in the user dialog)");
                    entityGroup.setCreatedTime(System.currentTimeMillis());
                }
                entityGroup.setEntityIds(new ArrayList<>(group.getUserIds() != null ? group.getUserIds() : List.of()));
                entityGroupService.saveEntityGroup(tenantId, entityGroup);
            }
            return savedSettings;
        }
    }

}
