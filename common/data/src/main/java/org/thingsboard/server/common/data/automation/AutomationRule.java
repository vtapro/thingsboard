// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.thingsboard.server.common.data.id.DeviceId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A time based automation: at the configured moment the platform sends a server side RPC request
 * to the device (e.g. switch a pump on or off). Rules are stored per tenant as JSON inside
 * AdminSettings (key "automation"), so no database schema change is required.
 */
@Schema
@Data
public class AutomationRule {

    private UUID id;

    private String name;

    private boolean enabled = true;

    /**
     * What makes this rule run: fixed schedule, interval, telemetry threshold, device state or alarm.
     */
    private AutomationTriggerType triggerType = AutomationTriggerType.SCHEDULE;

    /**
     * Repeat every N minutes/hours (triggerType = INTERVAL).
     */
    private AutomationInterval interval = new AutomationInterval();

    /**
     * Telemetry threshold (triggerType = TELEMETRY).
     */
    private AutomationCondition condition = new AutomationCondition();

    /**
     * Device connectivity (triggerType = DEVICE_STATE).
     */
    private AutomationDeviceState deviceState = new AutomationDeviceState();

    /**
     * Device alarm (triggerType = ALARM).
     */
    private AutomationAlarmTrigger alarm = new AutomationAlarmTrigger();

    private DeviceId deviceId;

    /**
     * Device name kept for display purposes only.
     */
    private String deviceName;

    /**
     * Server side RPC method, e.g. "setState".
     */
    private String method;

    /**
     * Server side RPC parameters, e.g. {"state": "ON"}.
     */
    private JsonNode params;

    /**
     * One way RPC does not wait for the device response (recommended for irrigation hardware).
     */
    private boolean oneWay = true;

    /**
     * Persistent RPC is stored in the database and delivered when the device reconnects.
     */
    private boolean persistent;

    /**
     * Run the action for this many minutes and then send the "off" request automatically
     * (e.g. switch the pump on for 10 minutes). 0 = no automatic stop.
     */
    private int durationMinutes;

    /**
     * RPC method used to stop the action, defaults to {@link #method}.
     */
    private String offMethod;

    /**
     * RPC parameters used to stop the action, e.g. {"state": "OFF"}.
     */
    private JsonNode offParams;

    private AutomationSchedule schedule = new AutomationSchedule();

    private Long lastRunTs;

    private String lastStatus;

    private String lastMessage;

    /**
     * Next planned execution time in epoch milliseconds, kept for display purposes.
     */
    private Long nextRunTs;

    /**
     * Bounded execution history (newest first), e.g. the last 20 runs.
     */
    private List<AutomationRun> runs = new ArrayList<>();

    /**
     * Epoch milliseconds of the pending automatic "off" request (see {@link #durationMinutes}).
     */
    private Long pendingOffTs;

    /**
     * Epoch milliseconds when the telemetry condition became true (used for "for N seconds").
     */
    private Long conditionSinceTs;

    /**
     * Last known device connectivity, used to detect online/offline edges.
     */
    private Boolean lastDeviceActive;

    /**
     * Last known alarm state, used to detect alarm appeared/cleared edges.
     */
    private Boolean lastAlarmActive;
}
