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
     * The two profiles of the customer users of PE are available out of the box: the user groups
     * {@code Customer Administrators} and {@code Customer Users} reference the pre-configured roles of the same
     * profile, and their members are the members of the entity groups of the same name. The tenant administrator
     * therefore switches the profile of a user in the "Manage owner and groups" dialog, and the role (hence the
     * permissions of the user) follows immediately.
     */
    private RbacUserGroupSettings ensureDefaultUserGroups(TenantId tenantId, RbacUserGroupSettings settings) {
        if (settings.getGroups() == null) {
            settings.setGroups(new ArrayList<>());
        }
        List<RbacEntityGroup> entityGroups = entityGroupService.getEntityGroupSettings(tenantId).getGroups();
        for (String name : DefaultEntityGroupService.CUSTOMER_PROFILE_GROUP_NAMES) {
            String roleName = DefaultEntityGroupService.CUSTOMER_ADMINS_GROUP_NAME.equals(name)
                    ? DefaultRoleService.CUSTOMER_ADMIN_ROLE_NAME : DefaultRoleService.CUSTOMER_USER_ROLE_NAME;
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
            String roleId = DefaultRoleService.defaultRoleId(tenantId, roleName);
            if (group.getRoleIds() == null) {
                group.setRoleIds(new ArrayList<>());
            }
            if (!group.getRoleIds().contains(roleId)) {
                group.getRoleIds().add(roleId);
            }
            group.setUserIds(new ArrayList<>(membersOfEntityGroup(entityGroups, name)));
        }
        return settings;
    }

    /** Members of the entity group of the same name: the dialog of the WEB UI is the single place that edits them. */
    private Set<String> membersOfEntityGroup(List<RbacEntityGroup> entityGroups, String name) {
        Set<String> members = new LinkedHashSet<>();
        if (entityGroups == null) {
            return members;
        }
        for (RbacEntityGroup group : entityGroups) {
            if ("USER".equals(group.getEntityType()) && name.equals(group.getName()) && group.getEntityIds() != null) {
                members.addAll(group.getEntityIds());
            }
        }
        return members;
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
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacUserGroupSettings.class);
        }
    }

}
