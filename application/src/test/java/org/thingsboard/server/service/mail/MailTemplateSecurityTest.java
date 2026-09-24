// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.mail;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MailTemplateSecurityTest {

    private static final String CLASS_INSTANTIATION = "${'freemarker.template.utility.Execute'?new()}";

    private static final String ATTACK_PAYLOAD = "${'freemarker.template.utility.Execute'?new()('id')}";

    /**
     * The mail template bodies are edited by tenant administrators, therefore the platform configuration (which
     * allows the {@code ?new} built-in to load arbitrary classes) must not be used to render them.
     */
    @Test
    public void platformConfigurationAllowsClassInstantiation() {
        Configuration platformConfiguration = new Configuration(Configuration.VERSION_2_3_32);
        // The class is instantiated, the failure comes from the value that can not be converted to a string.
        assertThatThrownBy(() -> render(platformConfiguration, CLASS_INSTANTIATION))
                .as("the platform configuration allows the template to instantiate arbitrary classes")
                .hasMessageNotContaining("not allowed");
    }

    @Test
    public void customTemplateConfigurationForbidsClassInstantiation() {
        Configuration customTemplateConfiguration = DefaultMailService.createCustomTemplateConfig(
                new Configuration(Configuration.VERSION_2_3_32));
        assertThatThrownBy(() -> render(customTemplateConfiguration, CLASS_INSTANTIATION))
                .hasMessageContaining("not allowed");
    }

    @Test
    public void customTemplateConfigurationForbidsRenderingTheAttackPayload() {
        Configuration customTemplateConfiguration = DefaultMailService.createCustomTemplateConfig(
                new Configuration(Configuration.VERSION_2_3_32));
        assertThatThrownBy(() -> render(customTemplateConfiguration, ATTACK_PAYLOAD))
                .hasMessageContaining("not allowed");
    }

    @Test
    public void customTemplateConfigurationForbidsJavaApiAccess() {
        Configuration customTemplateConfiguration = DefaultMailService.createCustomTemplateConfig(
                new Configuration(Configuration.VERSION_2_3_32));
        assertThatThrownBy(() -> render(customTemplateConfiguration, "${'x'?api.getBytes()}"))
                .hasMessageContaining("api");
    }

    @Test
    public void customTemplateConfigurationRendersSupportedTemplates() throws Exception {
        Configuration customTemplateConfiguration = DefaultMailService.createCustomTemplateConfig(
                new Configuration(Configuration.VERSION_2_3_32));
        Map<String, Object> model = new HashMap<>();
        model.put("targetEmail", "user@thingsboard.org");
        model.put("appTitle", "Green IQ");
        assertThat(render(customTemplateConfiguration, "<b>${appTitle}</b> - ${targetEmail}", model))
                .isEqualTo("<b>Green IQ</b> - user@thingsboard.org");
    }

    private String render(Configuration configuration, String templateContent) throws Exception {
        return render(configuration, templateContent, new HashMap<>());
    }

    private String render(Configuration configuration, String templateContent, Map<String, Object> model) throws Exception {
        Template template = new Template("custom.ftl", new StringReader(templateContent), configuration);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

}
