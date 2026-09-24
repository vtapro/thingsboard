// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.mail.MailTemplateSettings;

@Service
@RequiredArgsConstructor
public class DefaultMailTemplateService implements MailTemplateService {

    public static final String MAIL_TEMPLATES_SETTINGS_KEY = "mailTemplates";

    private final AdminSettingsService adminSettingsService;

    @Override
    public MailTemplateSettings getMailTemplateSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, MAIL_TEMPLATES_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new MailTemplateSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), MailTemplateSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load mail template settings!", e);
        }
    }

    @Override
    public MailTemplateSettings saveMailTemplateSettings(TenantId tenantId, MailTemplateSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, MAIL_TEMPLATES_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(MAIL_TEMPLATES_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings savedAdminSettings = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(savedAdminSettings.getJsonValue(), MailTemplateSettings.class);
    }

}

