// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.i18n.CustomTranslationSettings;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultCustomTranslationService implements CustomTranslationService {

    public static final String CUSTOM_TRANSLATION_SETTINGS_KEY = "customTranslation";

    private final AdminSettingsService adminSettingsService;

    @Override
    public Map<String, Map<String, String>> getCustomTranslations(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByKey(tenantId, CUSTOM_TRANSLATION_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new HashMap<>();
        }
        try {
            CustomTranslationSettings settings = JacksonUtil.convertValue(adminSettings.getJsonValue(), CustomTranslationSettings.class);
            return settings.getTranslations() != null ? settings.getTranslations() : new HashMap<>();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom translations!", e);
        }
    }

    @Override
    public Map<String, String> getCustomTranslations(TenantId tenantId, String locale) {
        return getCustomTranslations(tenantId).getOrDefault(locale, new HashMap<>());
    }

    @Override
    public Map<String, String> saveCustomTranslations(TenantId tenantId, String locale, Map<String, String> translations) {
        Map<String, Map<String, String>> all = getCustomTranslations(tenantId);
        if (translations == null || translations.isEmpty()) {
            all.remove(locale);
        } else {
            all.put(locale, translations);
        }
        return save(tenantId, all).getOrDefault(locale, new HashMap<>());
    }

    @Override
    public Map<String, Map<String, String>> deleteCustomTranslations(TenantId tenantId, String locale) {
        Map<String, Map<String, String>> all = getCustomTranslations(tenantId);
        all.remove(locale);
        return save(tenantId, all);
    }

    private Map<String, Map<String, String>> save(TenantId tenantId, Map<String, Map<String, String>> translations) {
        CustomTranslationSettings settings = new CustomTranslationSettings();
        settings.setTranslations(translations);
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByKey(tenantId, CUSTOM_TRANSLATION_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(CUSTOM_TRANSLATION_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.convertValue(saved.getJsonValue(), CustomTranslationSettings.class).getTranslations();
    }

}

