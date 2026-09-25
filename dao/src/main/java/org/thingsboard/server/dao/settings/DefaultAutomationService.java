// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.automation.AutomationRules;
import org.thingsboard.server.common.data.id.TenantId;

/**
 * Automation rules are stored as a JSON document in AdminSettings (key "automation"), so the fork
 * does not need any database schema change and can still be merged with upstream ThingsBoard.
 */
@Service
@RequiredArgsConstructor
public class DefaultAutomationService implements AutomationService {

    public static final String AUTOMATION_SETTINGS_KEY = "automation";

    private final AdminSettingsService adminSettingsService;

    @Override
    public AutomationRules getAutomationRules(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, AUTOMATION_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null || adminSettings.getJsonValue().isNull()) {
            return new AutomationRules();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), AutomationRules.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load automation rules!", e);
        }
    }

    @Override
    public AutomationRules saveAutomationRules(TenantId tenantId, AutomationRules rules) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, AUTOMATION_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(AUTOMATION_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(rules));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), AutomationRules.class);
    }

}
