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
}
