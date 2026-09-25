// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Container stored in AdminSettings under the "automation" key.
 */
@Schema
@Data
public class AutomationRules {

    private List<AutomationRule> rules = new ArrayList<>();
}
