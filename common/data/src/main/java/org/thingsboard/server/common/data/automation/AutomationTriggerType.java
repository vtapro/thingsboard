// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

/**
 * What makes an automation rule run.
 */
public enum AutomationTriggerType {
    /**
     * Fixed time: daily / selected days of week / cron / sunrise / sunset.
     */
    SCHEDULE,
    /**
     * Repeated every N minutes or hours (optionally only inside a time window) - e.g. irrigate 5
     * minutes every 2 hours between 05:00 and 18:00.
     */
    INTERVAL,
    /**
     * Telemetry threshold - e.g. soil moisture below 30% for 5 minutes.
     */
    TELEMETRY,
    /**
     * Device connectivity - e.g. turn the pump off when the controller goes offline.
     */
    DEVICE_STATE,
    /**
     * Alarm of the device - e.g. react when a "High pressure" alarm appears.
     */
    ALARM
}
