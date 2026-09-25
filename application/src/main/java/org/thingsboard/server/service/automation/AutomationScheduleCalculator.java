// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.thingsboard.server.common.data.automation.AutomationInterval;
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
            case ASTRONOMY -> next = nextAstronomy(from, schedule);
            default -> next = null;
        }
        return next == null ? null : next.toInstant().toEpochMilli();
    }

    /**
     * Next run of an interval trigger: repeat every N minutes/hours, optionally only inside a daily
     * time window (e.g. every 2 hours between 05:00 and 18:00).
     */
    public static Long nextIntervalTs(AutomationInterval interval, String timeZone, long fromTs, Long lastRunTs) {
        if (interval == null) {
            return null;
        }
        ZoneId zone = resolveZone(timeZone);
        long base = lastRunTs != null ? lastRunTs : fromTs;
        long candidateTs = base + interval.toMillis();
        if (candidateTs < fromTs) {
            candidateTs = fromTs;
        }
        LocalTime from = parseTime(interval.getFromTime());
        LocalTime to = parseTime(interval.getToTime());
        if (from == null || to == null) {
            return candidateTs;
        }
        ZonedDateTime candidate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candidateTs), zone);
        if (candidate.toLocalTime().isBefore(from) || candidate.toLocalTime().isAfter(to)) {
            candidate = candidate.toLocalDate().plusDays(1).atTime(from).atZone(zone);
        }
        return candidate.toInstant().toEpochMilli();
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
        if (schedule.getType() == AutomationScheduleType.ASTRONOMY) {
            return schedule.getLatitude() != null && schedule.getLongitude() != null
                    && nextRunTs(schedule, System.currentTimeMillis()) != null;
        }
        return true;
    }

    /**
     * Sunrise / sunset calculation (NOAA simplified algorithm) for the configured location.
     */
    private static ZonedDateTime nextAstronomy(ZonedDateTime from, AutomationSchedule schedule) {
        if (schedule.getLatitude() == null || schedule.getLongitude() == null) {
            return null;
        }
        boolean sunrise = !"SUNSET".equalsIgnoreCase(schedule.getAstronomyEvent());
        for (int dayOffset = 0; dayOffset <= 2; dayOffset++) {
            ZonedDateTime date = from.toLocalDate().plusDays(dayOffset).atStartOfDay(from.getZone());
            ZonedDateTime event = astronomyForDate(date, schedule.getLatitude(), schedule.getLongitude(), sunrise);
            if (event == null) {
                continue;
            }
            event = event.plusMinutes(schedule.getOffsetMinutes());
            if (event.isAfter(from)) {
                return event;
            }
        }
        return null;
    }

    private static ZonedDateTime astronomyForDate(ZonedDateTime date, double latitude, double longitude, boolean sunrise) {
        int dayOfYear = date.getDayOfYear();
        double lngHour = longitude / 15.0;
        double t = dayOfYear + ((sunrise ? 6.0 : 18.0) - lngHour) / 24.0;
        double meanAnomaly = 0.9856 * t - 3.289;
        double trueLongitude = meanAnomaly
                + 1.916 * Math.sin(Math.toRadians(meanAnomaly))
                + 0.020 * Math.sin(Math.toRadians(2 * meanAnomaly))
                + 282.634;
        trueLongitude = normalize(trueLongitude, 360);
        double rightAscension = normalize(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(trueLongitude)))), 360);
        double quadrantL = Math.floor(trueLongitude / 90) * 90;
        double quadrantRa = Math.floor(rightAscension / 90) * 90;
        rightAscension = (rightAscension + quadrantL - quadrantRa) / 15.0;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(trueLongitude));
        double cosDec = Math.cos(Math.asin(sinDec));
        double zenith = 90.833;
        double cosHourAngle = (Math.cos(Math.toRadians(zenith)) - sinDec * Math.sin(Math.toRadians(latitude)))
                / (cosDec * Math.cos(Math.toRadians(latitude)));
        if (cosHourAngle > 1 || cosHourAngle < -1) {
            // the sun does not rise / set at this location on this date
            return null;
        }
        double hourAngle = sunrise
                ? 360.0 - Math.toDegrees(Math.acos(cosHourAngle))
                : Math.toDegrees(Math.acos(cosHourAngle));
        hourAngle = hourAngle / 15.0;
        double localMeanTime = hourAngle + rightAscension - (0.06571 * t) - 6.622;
        double utcHours = normalize(localMeanTime - lngHour, 24);
        long minutesOfDay = Math.round(utcHours * 60);
        return date.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).plusMinutes(minutesOfDay)
                .withZoneSameInstant(date.getZone());
    }

    private static double normalize(double value, double range) {
        double result = value % range;
        return result < 0 ? result + range : result;
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
