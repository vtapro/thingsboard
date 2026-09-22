// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.mail;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Schema
@Data
public class MailTemplateSettings implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "When enabled, the platform mail templates are used and custom templates are ignored.")
    private boolean useSystemMailTemplates = true;

    @Schema(description = "Custom templates by template key, for example activation.ftl")
    private Map<String, MailTemplate> templates = new HashMap<>();

    @Data
    public static class MailTemplate implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "Mail subject. When empty, the default subject is used.")
        private String subject;

        @Schema(description = "Mail body. When empty, the default template is used.")
        private String body;

    }

}

