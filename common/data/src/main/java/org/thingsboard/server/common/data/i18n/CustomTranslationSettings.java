// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.i18n;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Schema
@Data
public class CustomTranslationSettings implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Custom translations by locale, for example { \"en_US\": { \"login.sign-in\": \"Sign in\" } }")
    private Map<String, Map<String, String>> translations = new HashMap<>();

}

