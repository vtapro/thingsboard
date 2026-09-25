// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

public enum AutomationScheduleType {
    /**
     * Runs every day at {@link AutomationSchedule#getTime()} local time.
     */
    DAILY,
    /**
     * Runs on the selected days of week at {@link AutomationSchedule#getTime()} local time.
     */
    WEEKLY,
    /**
     * Runs according to a 6 field Spring cron expression ({@link AutomationSchedule#getCron()}).
     */
    CRON
}
