// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacUserGroupSettings;

@Service
@RequiredArgsConstructor
public class DefaultUserGroupService implements UserGroupService {

    public static final String USER_GROUPS_SETTINGS_KEY = "userGroups";

    private final AdminSettingsService adminSettingsService;

    @Override
    public RbacUserGroupSettings getUserGroupSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, USER_GROUPS_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacUserGroupSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), RbacUserGroupSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load user group settings!", e);
        }
    }

    @Override
    public RbacUserGroupSettings saveUserGroupSettings(TenantId tenantId, RbacUserGroupSettings settings) {
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

