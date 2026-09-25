// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.thingsboard.server.config.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.automation.AutomationRule;
import org.thingsboard.server.common.data.automation.AutomationRules;
import org.thingsboard.server.common.data.automation.AutomationTriggerType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.AutomationService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.automation.AutomationExecutor;
import org.thingsboard.server.service.automation.AutomationScheduleCalculator;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

/**
 * Time based automations of the current tenant: switch a pump on at 06:00, stop it at 06:30, ...
 * A rule simply sends a server side RPC request to the selected device.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@TbCoreComponent
@RequestMapping("/api/tenant/automation")
public class AutomationController extends BaseController {

    private final AutomationService automationService;
    private final AutomationExecutor automationExecutor;
    private final DeviceService deviceService;

    @ApiOperation(value = "Get the automation rules (getAutomationRules)",
            notes = "Returns the automation rules of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping
    public AutomationRules getAutomationRules() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return automationService.getAutomationRules(getTenantId());
    }

    @ApiOperation(value = "Create or update an automation rule (saveAutomationRule)",
            notes = "Creates or updates a time based automation rule of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Automation rule saved"),
            @ApiResponse(responseCode = "400", description = "Invalid automation rule"),
            @ApiResponse(responseCode = "404", description = "Device not found")
    })
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping
    public AutomationRule saveAutomationRule(
            @Parameter(description = "A JSON value representing the automation rule.", schema = @Schema(implementation = AutomationRule.class))
            @RequestBody AutomationRule rule) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        TenantId tenantId = getTenantId();
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new ThingsboardException("Automation name is required", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        if (rule.getDeviceId() == null) {
            throw new ThingsboardException("Device is required", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        if (rule.getMethod() == null || rule.getMethod().isBlank()) {
            throw new ThingsboardException("RPC method is required", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        validateTrigger(rule);
        Device device = deviceService.findDeviceById(tenantId, rule.getDeviceId());
        if (device == null) {
            throw new ThingsboardException("Device not found", ThingsboardErrorCode.ITEM_NOT_FOUND);
        }
        rule.setDeviceName(device.getName());
        rule.setNextRunTs(computeNextRunTs(rule));

        AutomationRules rules = automationService.getAutomationRules(tenantId);
        List<AutomationRule> stored = rules.getRules() == null ? new ArrayList<>() : new ArrayList<>(rules.getRules());
        if (rule.getId() == null) {
            rule.setId(UUID.randomUUID());
            stored.add(rule);
        } else {
            int index = -1;
            for (int i = 0; i < stored.size(); i++) {
                if (rule.getId().equals(stored.get(i).getId())) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                stored.set(index, rule);
            } else {
                stored.add(rule);
            }
        }
        rules.setRules(stored);
        automationService.saveAutomationRules(tenantId, rules);
        return rule;
    }

    @ApiOperation(value = "Delete an automation rule (deleteAutomationRule)",
            notes = "Deletes an automation rule of the current tenant. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping("/{ruleId}")
    public void deleteAutomationRule(@Parameter(description = "Automation rule id") @PathVariable("ruleId") UUID ruleId)
            throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.DELETE);
        TenantId tenantId = getTenantId();
        AutomationRules rules = automationService.getAutomationRules(tenantId);
        List<AutomationRule> stored = rules.getRules() == null ? new ArrayList<>() : new ArrayList<>(rules.getRules());
        stored.removeIf(rule -> ruleId.equals(rule.getId()));
        rules.setRules(stored);
        automationService.saveAutomationRules(tenantId, rules);
    }

    @ApiOperation(value = "Enable or disable an automation rule (setAutomationRuleEnabled)",
            notes = "Enables or disables an automation rule without changing its schedule. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{ruleId}/enabled/{enabled}")
    public AutomationRule setAutomationRuleEnabled(
            @Parameter(description = "Automation rule id") @PathVariable("ruleId") UUID ruleId,
            @Parameter(description = "true to enable the rule") @PathVariable("enabled") boolean enabled) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        TenantId tenantId = getTenantId();
        AutomationRules rules = automationService.getAutomationRules(tenantId);
        AutomationRule found = findRule(rules, ruleId);
        if (found == null) {
            throw new ThingsboardException("Automation rule not found", ThingsboardErrorCode.ITEM_NOT_FOUND);
        }
        found.setEnabled(enabled);
        if (enabled) {
            found.setNextRunTs(AutomationScheduleCalculator.nextRunTs(found.getSchedule(), System.currentTimeMillis()));
        }
        automationService.saveAutomationRules(tenantId, rules);
        return found;
    }

    @ApiOperation(value = "Run an automation rule now (runAutomationRuleNow)",
            notes = "Sends the configured RPC request immediately, regardless of the schedule. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{ruleId}/run")
    public AutomationRule runAutomationRuleNow(@Parameter(description = "Automation rule id") @PathVariable("ruleId") UUID ruleId)
            throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        TenantId tenantId = getTenantId();
        AutomationRules rules = automationService.getAutomationRules(tenantId);
        AutomationRule found = findRule(rules, ruleId);
        if (found == null) {
            throw new ThingsboardException("Automation rule not found", ThingsboardErrorCode.ITEM_NOT_FOUND);
        }
        automationExecutor.execute(tenantId, found);
        automationService.saveAutomationRules(tenantId, rules);
        return found;
    }

    private AutomationRule findRule(AutomationRules rules, UUID ruleId) {
        if (rules.getRules() == null) {
            return null;
        }
        return rules.getRules().stream().filter(rule -> ruleId.equals(rule.getId())).findFirst().orElse(null);
    }

    private void validateTrigger(AutomationRule rule) throws ThingsboardException {
        AutomationTriggerType triggerType = rule.getTriggerType() == null
                ? AutomationTriggerType.SCHEDULE : rule.getTriggerType();
        switch (triggerType) {
            case INTERVAL -> {
                if (rule.getInterval() == null || rule.getInterval().toMillis() <= 0) {
                    throw new ThingsboardException("Interval must be greater than 0",
                            ThingsboardErrorCode.BAD_REQUEST_PARAMS);
                }
            }
            case TELEMETRY -> {
                if (rule.getCondition() == null || rule.getCondition().getKey() == null
                        || rule.getCondition().getKey().isBlank()) {
                    throw new ThingsboardException("Telemetry key of the condition is required",
                            ThingsboardErrorCode.BAD_REQUEST_PARAMS);
                }
            }
            case ALARM -> {
                if (rule.getAlarm() == null || rule.getAlarm().getAlarmType() == null
                        || rule.getAlarm().getAlarmType().isBlank()) {
                    throw new ThingsboardException("Alarm type is required",
                            ThingsboardErrorCode.BAD_REQUEST_PARAMS);
                }
            }
            case DEVICE_STATE -> {
                // nothing else to validate
            }
            default -> {
                if (!AutomationScheduleCalculator.isValid(rule.getSchedule())) {
                    throw new ThingsboardException("Schedule is invalid (check the time or the cron expression)",
                            ThingsboardErrorCode.BAD_REQUEST_PARAMS);
                }
            }
        }
    }

    private Long computeNextRunTs(AutomationRule rule) {
        long now = System.currentTimeMillis();
        AutomationTriggerType triggerType = rule.getTriggerType() == null
                ? AutomationTriggerType.SCHEDULE : rule.getTriggerType();
        if (triggerType == AutomationTriggerType.INTERVAL) {
            String timeZone = rule.getSchedule() != null ? rule.getSchedule().getTimeZone() : null;
            return AutomationScheduleCalculator.nextIntervalTs(rule.getInterval(), timeZone, now, null);
        }
        if (triggerType == AutomationTriggerType.SCHEDULE) {
            return AutomationScheduleCalculator.nextRunTs(rule.getSchedule(), now);
        }
        return null;
    }
}
