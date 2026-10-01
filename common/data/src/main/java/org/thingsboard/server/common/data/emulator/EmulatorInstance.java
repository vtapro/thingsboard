// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.thingsboard.server.common.data.id.DeviceId;

import java.util.UUID;

/**
 * One emulator of a tenant: a device that publishes synthetic telemetry, its scenario and its dashboard.
 * Instances are stored as JSON in AdminSettings (key "emulators"), so no database schema change is required.
 */
@Schema
@Data
public class EmulatorInstance {

    private String id;

    private String profileId;

    private String profileName;

    private String category;

    /**
     * Name of the emulator, also used as the device name (e.g. "solar-inverter-50kw-001").
     */
    private String name;

    private DeviceId deviceId;

    private String deviceName;

    private EmulatorStatus status = EmulatorStatus.STOPPED;

    private String scenario;

    private int intervalSeconds = 10;

    private long createdTime;

    /**
     * Last time a telemetry message was published (epoch millis, 0 = never).
     */
    private long lastActivityTs;

    /**
     * Dashboard created for this emulator (nullable while it does not exist yet).
     */
    private UUID dashboardId;

    private String dashboardTitle;

    /**
     * Number of telemetry messages published since the emulator was created.
     */
    private long publishedMessages;
}
