// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.thingsboard.server.common.data.automation.AutomationSchedule;
import org.thingsboard.server.common.data.automation.AutomationScheduleType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Computes the next execution time of an automation rule. Pure functions, so the calculation can be
 * unit tested without a Spring context.
 */
@Slf4j
public final class AutomationScheduleCalculator {

    private AutomationScheduleCalculator() {
    }

    /**
     * @return epoch milliseconds of the next run strictly after {@code fromTs}, or null when the
     * schedule is invalid / empty.
     */
    public static Long nextRunTs(AutomationSchedule schedule, long fromTs) {
        if (schedule == null || schedule.getType() == null) {
            return null;
        }
        ZoneId zone = resolveZone(schedule.getTimeZone());
        ZonedDateTime from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromTs), zone);
        ZonedDateTime next;
        switch (schedule.getType()) {
            case DAILY -> next = nextDaily(from, parseTime(schedule.getTime()));
            case WEEKLY -> next = nextWeekly(from, parseTime(schedule.getTime()), schedule.getDaysOfWeek());
            case CRON -> next = nextCron(from, schedule.getCron());
            default -> next = null;
        }
        return next == null ? null : next.toInstant().toEpochMilli();
    }

    /**
     * @return true when the schedule may be stored (time/cron parse and there is at least one result).
     */
    public static boolean isValid(AutomationSchedule schedule) {
        if (schedule == null || schedule.getType() == null) {
            return false;
        }
        if (schedule.getType() == AutomationScheduleType.CRON) {
            return schedule.getCron() != null && !schedule.getCron().isBlank()
                    && CronExpression.isValidExpression(schedule.getCron())
                    && nextRunTs(schedule, System.currentTimeMillis()) != null;
        }
        LocalTime time = parseTime(schedule.getTime());
        if (time == null) {
            return false;
        }
        if (schedule.getType() == AutomationScheduleType.WEEKLY) {
            List<Integer> days = schedule.getDaysOfWeek();
            return days != null && days.stream().anyMatch(d -> d != null && d >= 1 && d <= 7);
        }
        return true;
    }

    private static ZonedDateTime nextDaily(ZonedDateTime from, LocalTime time) {
        if (time == null) {
            return null;
        }
        ZonedDateTime candidate = from.with(time);
        if (!candidate.isAfter(from)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static ZonedDateTime nextWeekly(ZonedDateTime from, LocalTime time, List<Integer> daysOfWeek) {
        if (time == null || daysOfWeek == null || daysOfWeek.isEmpty()) {
            return null;
        }
        for (int i = 0; i <= 7; i++) {
            ZonedDateTime candidate = from.with(time).plusDays(i);
            DayOfWeek day = candidate.getDayOfWeek();
            if (daysOfWeek.contains(day.getValue()) && candidate.isAfter(from)) {
                return candidate;
            }
        }
        return null;
    }

    private static ZonedDateTime nextCron(ZonedDateTime from, String cron) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            return expression.next(from);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression [{}]: {}", cron, e.getMessage());
            return null;
        }
    }

    private static LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(time.trim());
        } catch (DateTimeParseException e) {
            log.warn("Invalid automation schedule time [{}]", time);
            return null;
        }
    }

    private static ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (Exception e) {
            log.warn("Unknown time zone [{}], falling back to the server time zone", timeZone);
            return ZoneId.systemDefault();
        }
    }
}
