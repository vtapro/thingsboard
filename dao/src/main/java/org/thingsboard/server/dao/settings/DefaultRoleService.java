// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;
import org.thingsboard.server.common.data.rbac.RbacUserGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultRoleService implements RoleService {

    public static final String ROLES_SETTINGS_KEY = "roles";

    private static final String EFFECTIVE_ROLE_ID = "effective";

    private final AdminSettingsService adminSettingsService;
    private final UserGroupService userGroupService;

    @Override
    public RbacRoleSettings getRoleSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ROLES_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacRoleSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), RbacRoleSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load roles settings!", e);
        }
    }

    @Override
    public RbacRoleSettings saveRoleSettings(TenantId tenantId, RbacRoleSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ROLES_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(ROLES_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacRoleSettings.class);
    }

    @Override
    public List<RbacRole> getEffectiveRoles(TenantId tenantId, String userId) {
        List<RbacRole> roles = getRoleSettings(tenantId).getRoles();
        if (roles == null || roles.isEmpty() || userId == null) {
            return List.of();
        }
        Set<String> roleIds = new HashSet<>();
        for (RbacRole role : roles) {
            if (role.getUserIds() != null && role.getUserIds().contains(userId)) {
                roleIds.add(role.getId());
            }
        }
        for (RbacUserGroup userGroup : getSafeUserGroups(tenantId)) {
            if (userGroup.getRoleIds() != null && userGroup.getUserIds() != null
                    && userGroup.getUserIds().contains(userId)) {
                roleIds.addAll(userGroup.getRoleIds());
            }
        }
        List<RbacRole> effectiveRoles = new ArrayList<>();
        for (RbacRole role : roles) {
            if (roleIds.contains(role.getId())) {
                effectiveRoles.add(role);
            }
        }
        return effectiveRoles;
    }

    @Override
    public RbacRole getEffectiveRole(TenantId tenantId, String userId) {
        List<RbacRole> effectiveRoles = getEffectiveRoles(tenantId, userId);
        if (effectiveRoles.isEmpty()) {
            return null;
        }
        RbacRole effective = new RbacRole();
        effective.setId(EFFECTIVE_ROLE_ID);
        effective.setName(EFFECTIVE_ROLE_ID);
        for (RbacRole role : effectiveRoles) {
            mergePermissions(role, effective);
        }
        return effective;
    }

    private List<RbacUserGroup> getSafeUserGroups(TenantId tenantId) {
        List<RbacUserGroup> groups = userGroupService.getUserGroupSettings(tenantId).getGroups();
        return groups != null ? groups : List.of();
    }

    /**
     * Roles are additive: the effective permissions of a user are the union of the permissions of all its roles.
     */
    private static void mergePermissions(RbacRole source, RbacRole target) {
        if (source.getPermissions() != null) {
            source.getPermissions().forEach((resource, operations) -> {
                List<String> merged = target.getPermissions().computeIfAbsent(resource, r -> new ArrayList<>());
                for (String operation : operations) {
                    if (!merged.contains(operation)) {
                        merged.add(operation);
                    }
                }
            });
        }
        if (source.getScopedPermissions() != null) {
            source.getScopedPermissions().forEach((resource, byOperation) ->
                    byOperation.forEach((operation, groupIds) -> {
                        List<String> merged = target.getScopedPermissions()
                                .computeIfAbsent(resource, r -> new HashMap<>())
                                .computeIfAbsent(operation, o -> new ArrayList<>());
                        for (String groupId : groupIds) {
                            if (!merged.contains(groupId)) {
                                merged.add(groupId);
                            }
                        }
                    }));
        }
        if (source.isOwnCustomerOnly()) {
            target.setOwnCustomerOnly(true);
        }
    }

}
