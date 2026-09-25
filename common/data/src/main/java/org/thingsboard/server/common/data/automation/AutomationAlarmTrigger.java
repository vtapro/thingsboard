// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import lombok.Data;

/**
 * Alarm trigger, e.g. "when a High pressure alarm appears on the device".
 */
@Data
public class AutomationAlarmTrigger {

    /**
     * Alarm type configured in the device profile / rule chain, e.g. "High temperature".
     */
    private String alarmType;

    /**
     * ACTIVE (alarm appeared) or CLEARED (alarm is gone).
     */
    private String event = "ACTIVE";
}
