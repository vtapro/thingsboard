// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

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
            String params = rule.getParams() == null || rule.getParams().isNull() ? "{}" : JacksonUtil.toString(rule.getParams());
            ToDeviceRpcRequestBody body = new ToDeviceRpcRequestBody(rule.getMethod(), params);
            long expTime = now + TimeUnit.SECONDS.toMillis(rule.isPersistent() ? 3600 : 30);
            ToDeviceRpcRequest request = new ToDeviceRpcRequest(UUID.randomUUID(), tenantId, deviceId,
                    rule.isOneWay(), expTime, body, rule.isPersistent(), null, null);
            deviceRpcService.processRestApiRpcRequest(request, response ->
                            log.debug("[{}][{}] Automation RPC response: {}", tenantId, rule.getId(), response),
                    null);
            addRun(rule, now, "OK", "RPC '" + rule.getMethod() + "' sent to " + device.getName());
            log.info("[{}][{}] Automation '{}' executed: {} -> {}", tenantId, rule.getId(), rule.getName(),
                    device.getName(), rule.getMethod());
        } catch (Exception e) {
            log.warn("[{}][{}] Automation '{}' failed", tenantId, rule.getId(), rule.getName(), e);
            addRun(rule, now, "FAILED", e.getMessage());
        }
    }

    private void addRun(AutomationRule rule, long ts, String status, String message) {
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
