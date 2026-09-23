// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.menu.CustomMenuSettings;

@Service
@RequiredArgsConstructor
public class DefaultCustomMenuService implements CustomMenuService {

    public static final String CUSTOM_MENU_SETTINGS_KEY = "customMenu";

    private final AdminSettingsService adminSettingsService;

    @Override
    public CustomMenuSettings getCustomMenuSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, CUSTOM_MENU_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new CustomMenuSettings();
        }
        try {
            return JacksonUtil.convertValue(adminSettings.getJsonValue(), CustomMenuSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom menu settings!", e);
        }
    }

    @Override
    public CustomMenuSettings saveCustomMenuSettings(TenantId tenantId, CustomMenuSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, CUSTOM_MENU_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(CUSTOM_MENU_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.convertValue(saved.getJsonValue(), CustomMenuSettings.class);
    }

}

