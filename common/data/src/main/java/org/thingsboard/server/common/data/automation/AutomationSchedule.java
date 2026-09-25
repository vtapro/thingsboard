// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema
@Data
public class AutomationSchedule {

    /**
     * Local time zone used to evaluate the schedule, e.g. "Asia/Ho_Chi_Minh".
     */
    private String timeZone = "Asia/Ho_Chi_Minh";

    private AutomationScheduleType type = AutomationScheduleType.DAILY;

    /**
     * Local time in HH:mm format, used by DAILY and WEEKLY schedules.
     */
    private String time = "06:00";

    /**
     * ISO day of week numbers (1 = Monday ... 7 = Sunday), used by WEEKLY schedules.
     */
    private List<Integer> daysOfWeek = new ArrayList<>();

    /**
     * 6 field cron expression (second minute hour day-of-month month day-of-week), used by CRON schedules.
     */
    private String cron;
}
