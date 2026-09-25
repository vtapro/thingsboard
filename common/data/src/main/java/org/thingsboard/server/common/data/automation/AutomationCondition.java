// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import lombok.Data;

/**
 * Telemetry based condition, e.g. soilMoisture LT 30 for 5 minutes with 30 minutes cooldown.
 */
@Data
public class AutomationCondition {

    private String key;

    /**
     * GT, GTE, LT, LTE, EQ, NE.
     */
    private String operator = "LT";

    private double value;

    /**
     * The condition must hold this long before the action runs (0 = immediately).
     */
    private int forSeconds;

    /**
     * Minimum delay between two runs of this rule (0 = no cooldown).
     */
    private int cooldownMinutes;

    public boolean test(double actual) {
        String op = operator == null ? "EQ" : operator.toUpperCase();
        switch (op) {
            case "GT": return actual > value;
            case "GTE": return actual >= value;
            case "LT": return actual < value;
            case "LTE": return actual <= value;
            case "NE": return actual != value;
            default: return actual == value;
        }
    }
}
