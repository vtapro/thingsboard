// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.whiteLabeling.WhiteLabelingSettings;
import org.thingsboard.server.dao.service.ConstraintValidator;

@Service
@RequiredArgsConstructor
public class DefaultWhiteLabelingService implements WhiteLabelingService {

    public static final String WHITE_LABELING_SETTINGS_KEY = "whiteLabeling";

    private final AdminSettingsService adminSettingsService;

    @Override
    public WhiteLabelingSettings getWhiteLabelingSettings() {
        return getWhiteLabelingSettings(TenantId.SYS_TENANT_ID);
    }

    @Override
    public WhiteLabelingSettings getWhiteLabelingSettings(TenantId tenantId) {
        WhiteLabelingSettings settings = findWhiteLabelingSettings(tenantId);
        if (settings == null && !TenantId.SYS_TENANT_ID.equals(tenantId)) {
            settings = findWhiteLabelingSettings(TenantId.SYS_TENANT_ID);
        }
        return settings != null ? settings : new WhiteLabelingSettings();
    }

    private WhiteLabelingSettings findWhiteLabelingSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, WHITE_LABELING_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return null;
        }
        try {
            return JacksonUtil.convertValue(adminSettings.getJsonValue(), WhiteLabelingSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load white labeling settings!", e);
        }
    }

    @Override
    public WhiteLabelingSettings saveWhiteLabelingSettings(WhiteLabelingSettings settings) {
        return saveWhiteLabelingSettings(TenantId.SYS_TENANT_ID, settings);
    }

    @Override
    public WhiteLabelingSettings saveWhiteLabelingSettings(TenantId tenantId, WhiteLabelingSettings settings) {
        ConstraintValidator.validateFields(settings);
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, WHITE_LABELING_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(WHITE_LABELING_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings savedAdminSettings = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.convertValue(savedAdminSettings.getJsonValue(), WhiteLabelingSettings.class);
    }

}
