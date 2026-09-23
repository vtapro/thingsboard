// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;

@Service
@RequiredArgsConstructor
public class DefaultRoleService implements RoleService {

    public static final String ROLES_SETTINGS_KEY = "roles";

    private final AdminSettingsService adminSettingsService;

    @Override
    public RbacRoleSettings getRoleSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ROLES_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacRoleSettings();
        }
        try {
            return JacksonUtil.convertValue(adminSettings.getJsonValue(), RbacRoleSettings.class);
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
        return JacksonUtil.convertValue(saved.getJsonValue(), RbacRoleSettings.class);
    }

}

