// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.whiteLabeling.WhiteLabelingSettings;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.dao.service.ConstraintValidator;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaultWhiteLabelingService implements WhiteLabelingService {

    public static final String WHITE_LABELING_SETTINGS_KEY = "whiteLabeling";

    /**
     * The custom CSS is served to every user of the tenant, including the unauthenticated visitors of the login page,
     * so the constructs that turn a stylesheet into a data exfiltration or UI redressing vector are rejected.
     */
    private static final int MAX_ADVANCED_CSS_LENGTH = 64 * 1024;

    private static final Pattern FORBIDDEN_CSS = Pattern.compile(
            "@import|expression\\s*\\(|javascript:|vbscript:|behavior\\s*:|url\\s*\\(|image-set\\s*\\(|-moz-binding|</?style",
            Pattern.CASE_INSENSITIVE);

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
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), WhiteLabelingSettings.class);
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
        validateAdvancedCss(settings);
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, WHITE_LABELING_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(WHITE_LABELING_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings savedAdminSettings = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(savedAdminSettings.getJsonValue(), WhiteLabelingSettings.class);
    }

    /**
     * Rejects the custom CSS that could leak data or overlay the UI of the other users of the tenant. The remote
     * references ({@code url()}, {@code @import}) are blocked because the browser would request them with the
     * credentials of the visitor, and the legacy script sinks are blocked as well.
     *
     * <p>Package private for the tests.
     */
    static void validateAdvancedCss(WhiteLabelingSettings settings) {
        String css = settings.getAdvancedCss();
        if (css == null || css.isBlank()) {
            return;
        }
        if (css.length() > MAX_ADVANCED_CSS_LENGTH) {
            throw new IncorrectParameterException("The custom CSS must not be longer than "
                    + MAX_ADVANCED_CSS_LENGTH + " characters!");
        }
        String normalized = css.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_CSS.matcher(normalized).find()) {
            throw new IncorrectParameterException("The custom CSS must not contain remote references "
                    + "(url(), @import) or script constructs!");
        }
        if (count(css, '{') != count(css, '}')) {
            throw new IncorrectParameterException("The custom CSS has unbalanced braces!");
        }
    }

    private static long count(String value, char c) {
        return value.chars().filter(ch -> ch == c).count();
    }

}
