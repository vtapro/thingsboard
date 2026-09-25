// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import lombok.Data;

/**
 * "Repeat every N minutes/hours" configuration, optionally limited to a daily time window.
 */
@Data
public class AutomationInterval {

    private int value = 2;

    /**
     * MINUTES or HOURS.
     */
    private String unit = "HOURS";

    /**
     * Optional time window in HH:mm local time (from), empty means "all day".
     */
    private String fromTime;

    /**
     * Optional time window in HH:mm local time (to), empty means "all day".
     */
    private String toTime;

    public long toMillis() {
        long base = "MINUTES".equalsIgnoreCase(unit) ? 60_000L : 3_600_000L;
        return Math.max(1, value) * base;
    }
}
