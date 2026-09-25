// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.DeviceInfo;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.automation.AutomationCondition;
import org.thingsboard.server.common.data.automation.AutomationRule;
import org.thingsboard.server.common.data.automation.AutomationRules;
import org.thingsboard.server.common.data.automation.AutomationScheduleType;
import org.thingsboard.server.common.data.automation.AutomationTriggerType;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.AutomationService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs the automation rules: fixed schedules (daily / weekly / cron / sunrise / sunset), intervals,
 * telemetry thresholds, device connectivity and alarms. The scheduler only triggers on the tb-core
 * instance that owns the system partition, so a multi replica deployment never executes a rule twice.
 */
@TbCoreComponent
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationScheduler {

    private static final int TENANT_PAGE_SIZE = 500;
    private static final int MAX_TENANTS = 10000;

    private final AutomationService automationService;
    private final AutomationExecutor automationExecutor;
    private final TenantService tenantService;
    private final PartitionService partitionService;
    private final DeviceService deviceService;
    private final TimeseriesService timeseriesService;
    private final AlarmService alarmService;

    @Scheduled(initialDelayString = "${automation.scheduler.initial-delay-ms:30000}",
            fixedDelayString = "${automation.scheduler.interval-ms:15000}")
    public void processDueRules() {
        if (!partitionService.isSystemPartitionMine(ServiceType.TB_CORE)) {
            // another tb-core replica owns the system partition
            return;
        }
        long now = System.currentTimeMillis();
        for (TenantId tenantId : listTenantIds()) {
            try {
                processTenant(tenantId, now);
            } catch (Exception e) {
                log.warn("[{}] Failed to process automation rules", tenantId, e);
            }
        }
    }

    private void processTenant(TenantId tenantId, long now) {
        AutomationRules rules = automationService.getAutomationRules(tenantId);
        if (rules.getRules() == null || rules.getRules().isEmpty()) {
            return;
        }
        boolean changed = false;
        for (AutomationRule rule : rules.getRules()) {
            if (rule.getTriggerType() == null) {
                rule.setTriggerType(AutomationTriggerType.SCHEDULE);
                changed = true;
            }
            // A pending automatic "off" is always delivered, even if the rule was disabled meanwhile.
            if (rule.getPendingOffTs() != null && rule.getPendingOffTs() <= now) {
                automationExecutor.executeOff(tenantId, rule);
                changed = true;
            }
            if (!rule.isEnabled() || rule.getDeviceId() == null) {
                continue;
            }
            switch (rule.getTriggerType()) {
                case TELEMETRY -> changed |= evaluateTelemetry(tenantId, rule, now);
                case DEVICE_STATE -> changed |= evaluateDeviceState(tenantId, rule, now);
                case ALARM -> changed |= evaluateAlarm(tenantId, rule, now);
                default -> changed |= evaluateSchedule(tenantId, rule, now);
            }
        }
        if (changed) {
            automationService.saveAutomationRules(tenantId, rules);
        }
    }

    // ------------------------------------------------------------------ schedules / intervals -----

    private boolean evaluateSchedule(TenantId tenantId, AutomationRule rule, long now) {
        Long nextRunTs = rule.getNextRunTs();
        if (nextRunTs == null) {
            rule.setNextRunTs(computeNextRunTs(rule, now, now));
            return true;
        }
        if (nextRunTs > now) {
            return false;
        }
        automationExecutor.execute(tenantId, rule, null);
        rule.setNextRunTs(computeNextRunTs(rule, now, now));
        return true;
    }

    private Long computeNextRunTs(AutomationRule rule, long now, Long lastRunTs) {
        if (rule.getTriggerType() == AutomationTriggerType.INTERVAL) {
            String timeZone = rule.getSchedule() != null ? rule.getSchedule().getTimeZone() : null;
            return AutomationScheduleCalculator.nextIntervalTs(rule.getInterval(), timeZone, now,
                    lastRunTs != null ? lastRunTs : rule.getLastRunTs());
        }
        if (rule.getSchedule() == null) {
            return null;
        }
        Long next = AutomationScheduleCalculator.nextRunTs(rule.getSchedule(), now);
        if (next == null && rule.getSchedule().getType() == AutomationScheduleType.ASTRONOMY) {
            log.warn("[{}] Cannot compute the sunrise/sunset time, check the latitude/longitude", rule.getId());
        }
        return next;
    }

    // ------------------------------------------------------------------ telemetry threshold ------

    private boolean evaluateTelemetry(TenantId tenantId, AutomationRule rule, long now) {
        AutomationCondition condition = rule.getCondition();
        if (condition == null || condition.getKey() == null || condition.getKey().isBlank()) {
            return false;
        }
        Double value = readLatestValue(tenantId, rule.getDeviceId(), condition.getKey());
        if (value == null) {
            return false;
        }
        boolean matched = condition.test(value);
        boolean changed = false;
        if (!matched) {
            if (rule.getConditionSinceTs() != null) {
                rule.setConditionSinceTs(null);
                changed = true;
            }
            return changed;
        }
        if (rule.getConditionSinceTs() == null) {
            rule.setConditionSinceTs(now);
            changed = true;
        }
        long heldMs = now - rule.getConditionSinceTs();
        if (heldMs < TimeUnit.SECONDS.toMillis(Math.max(0, condition.getForSeconds()))) {
            rule.setNextRunTs(rule.getConditionSinceTs() + TimeUnit.SECONDS.toMillis(condition.getForSeconds()));
            return true;
        }
        long cooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0, condition.getCooldownMinutes()));
        if (cooldownMs > 0 && rule.getLastRunTs() != null && now - rule.getLastRunTs() < cooldownMs) {
            rule.setNextRunTs(rule.getLastRunTs() + cooldownMs);
            return true;
        }
        automationExecutor.execute(tenantId, rule,
                condition.getKey() + " " + operatorLabel(condition) + " " + condition.getValue() + " (=" + value + ")");
        rule.setConditionSinceTs(now);
        rule.setNextRunTs(cooldownMs > 0 ? now + cooldownMs : now);
        return true;
    }

    private Double readLatestValue(TenantId tenantId, DeviceId deviceId, String key) {
        try {
            Optional<TsKvEntry> latest = timeseriesService.findLatest(tenantId, deviceId, key)
                    .get(5, TimeUnit.SECONDS);
            return latest.map(entry -> {
                Object value = entry.getValue();
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
                try {
                    return Double.parseDouble(String.valueOf(value));
                } catch (Exception e) {
                    return null;
                }
            }).orElse(null);
        } catch (Exception e) {
            log.warn("[{}] Cannot read telemetry '{}' of {}", tenantId, key, deviceId, e);
            return null;
        }
    }

    private String operatorLabel(AutomationCondition condition) {
        return switch (condition.getOperator() == null ? "EQ" : condition.getOperator().toUpperCase()) {
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "NE" -> "!=";
            default -> "=";
        };
    }

    // ------------------------------------------------------------------ device connectivity -------

    private boolean evaluateDeviceState(TenantId tenantId, AutomationRule rule, long now) {
        DeviceInfo info = deviceService.findDeviceInfoById(tenantId, rule.getDeviceId());
        if (info == null) {
            return false;
        }
        boolean active = info.isActive();
        boolean wantOnline = rule.getDeviceState() == null
                || !"OFFLINE".equalsIgnoreCase(rule.getDeviceState().getState());
        Boolean previous = rule.getLastDeviceActive();
        boolean changed = false;
        if (previous == null || previous != active) {
            rule.setLastDeviceActive(active);
            changed = true;
        }
        if (active == wantOnline && (previous == null || previous != active)) {
            automationExecutor.execute(tenantId, rule, active ? "device is online" : "device is offline");
            rule.setNextRunTs(null);
        }
        return changed;
    }

    // ------------------------------------------------------------------ alarm ---------------------

    private boolean evaluateAlarm(TenantId tenantId, AutomationRule rule, long now) {
        String alarmType = rule.getAlarm() != null ? rule.getAlarm().getAlarmType() : null;
        if (alarmType == null || alarmType.isBlank()) {
            return false;
        }
        Alarm alarm = alarmService.findLatestActiveByOriginatorAndType(tenantId, rule.getDeviceId(), alarmType);
        boolean active = alarm != null;
        Boolean previous = rule.getLastAlarmActive();
        boolean changed = false;
        if (previous == null || previous != active) {
            rule.setLastAlarmActive(active);
            changed = true;
        }
        boolean wantActive = rule.getAlarm() == null || !"CLEARED".equalsIgnoreCase(rule.getAlarm().getEvent());
        if (active == wantActive && (previous == null || previous != active)) {
            automationExecutor.execute(tenantId, rule,
                    "alarm '" + alarmType + "' " + (active ? "active" : "cleared"));
        }
        return changed;
    }

    private List<TenantId> listTenantIds() {
        List<TenantId> result = new ArrayList<>();
        PageLink pageLink = new PageLink(TENANT_PAGE_SIZE);
        while (pageLink != null && result.size() < MAX_TENANTS) {
            PageData<TenantId> page = tenantService.findTenantsIds(pageLink);
            result.addAll(page.getData());
            pageLink = page.hasNext() ? pageLink.nextPageLink() : null;
        }
        return result;
    }
}
