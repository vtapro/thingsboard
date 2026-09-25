// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.automation.AutomationRule;
import org.thingsboard.server.common.data.automation.AutomationRun;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rpc.ToDeviceRpcRequestBody;
import org.thingsboard.server.common.msg.rpc.ToDeviceRpcRequest;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.rpc.TbCoreDeviceRpcService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sends the server side RPC request of one automation rule to the device and records the outcome.
 * Supports timed actions: switch the device on, then switch it off automatically after
 * {@link AutomationRule#getDurationMinutes()} minutes (the pending "off" is stored in the rule, so
 * it survives a restart of the service).
 */
@TbCoreComponent
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationExecutor {

    /**
     * Number of executions kept per rule (stored inside the rule JSON).
     */
    public static final int MAX_RUNS_HISTORY = 20;

    private final DeviceService deviceService;
    private final TbCoreDeviceRpcService deviceRpcService;

    public void execute(TenantId tenantId, AutomationRule rule) {
        execute(tenantId, rule, null);
    }

    /**
     * @param reason human readable reason shown in the run history (may be null)
     */
    public void execute(TenantId tenantId, AutomationRule rule, String reason) {
        long now = System.currentTimeMillis();
        rule.setLastRunTs(now);
        try {
            DeviceId deviceId = rule.getDeviceId();
            if (deviceId == null) {
                addRun(rule, now, "FAILED", "Device is not selected");
                return;
            }
            Device device = deviceService.findDeviceById(tenantId, deviceId);
            if (device == null) {
                addRun(rule, now, "FAILED", "Device not found");
                return;
            }
            rule.setDeviceName(device.getName());
            if (rule.getMethod() == null || rule.getMethod().isBlank()) {
                addRun(rule, now, "FAILED", "RPC method is not configured");
                return;
            }
            sendRpc(tenantId, deviceId, rule.getMethod(), rule.getParams(), rule.isOneWay(), rule.isPersistent());
            String message = "RPC '" + rule.getMethod() + "' sent to " + device.getName()
                    + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "");
            if (rule.getDurationMinutes() > 0) {
                long offTs = now + TimeUnit.MINUTES.toMillis(rule.getDurationMinutes());
                rule.setPendingOffTs(offTs);
                message = message + ", auto off after " + rule.getDurationMinutes() + " minutes";
            }
            addRun(rule, now, "OK", message);
            log.info("[{}][{}] Automation '{}' executed: {} -> {}", tenantId, rule.getId(), rule.getName(),
                    device.getName(), rule.getMethod());
        } catch (Exception e) {
            log.warn("[{}][{}] Automation '{}' failed", tenantId, rule.getId(), rule.getName(), e);
            addRun(rule, now, "FAILED", e.getMessage());
        }
    }

    /**
     * Sends the configured "off" request (used by {@link AutomationRule#getDurationMinutes()}).
     */
    public void executeOff(TenantId tenantId, AutomationRule rule) {
        long now = System.currentTimeMillis();
        try {
            String method = rule.getOffMethod() == null || rule.getOffMethod().isBlank()
                    ? rule.getMethod() : rule.getOffMethod();
            JsonNode params = rule.getOffParams() != null && !rule.getOffParams().isNull()
                    ? rule.getOffParams() : JacksonUtil.toJsonNode("{\"state\":\"OFF\"}");
            sendRpc(tenantId, rule.getDeviceId(), method, params, rule.isOneWay(), rule.isPersistent());
            rule.setPendingOffTs(null);
            addRun(rule, now, "OK", "RPC '" + method + "' sent (auto off)");
            log.info("[{}][{}] Automation '{}' auto off: {}", tenantId, rule.getId(), rule.getName(), method);
        } catch (Exception e) {
            log.warn("[{}][{}] Automation '{}' auto off failed", tenantId, rule.getId(), rule.getName(), e);
            rule.setPendingOffTs(null);
            addRun(rule, now, "FAILED", "auto off: " + e.getMessage());
        }
    }

    private void sendRpc(TenantId tenantId, DeviceId deviceId, String method, JsonNode params,
                         boolean oneWay, boolean persistent) {
        String paramsJson = params == null || params.isNull() ? "{}" : JacksonUtil.toString(params);
        ToDeviceRpcRequestBody body = new ToDeviceRpcRequestBody(method, paramsJson);
        long expTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(persistent ? 3600 : 30);
        ToDeviceRpcRequest request = new ToDeviceRpcRequest(UUID.randomUUID(), tenantId, deviceId,
                oneWay, expTime, body, persistent, null, null);
        deviceRpcService.processRestApiRpcRequest(request, response ->
                log.debug("[{}] Automation RPC response: {}", tenantId, response), null);
    }

    public void addRun(AutomationRule rule, long ts, String status, String message) {
        rule.setLastStatus(status);
        rule.setLastMessage(message);
        List<AutomationRun> runs = rule.getRuns() == null ? new ArrayList<>() : rule.getRuns();
        runs.add(0, new AutomationRun(ts, status, message));
        if (runs.size() > MAX_RUNS_HISTORY) {
            runs = new ArrayList<>(runs.subList(0, MAX_RUNS_HISTORY));
        }
        rule.setRuns(runs);
    }
}
