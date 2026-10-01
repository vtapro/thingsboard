// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog entry of a virtual device: what it emulates, which signals it publishes and which scenarios it supports.
 * The catalog is defined by the platform (like the emulator catalog of ThingsBoard PE) and is the same for every
 * tenant; only the emulator instances created out of a profile are stored per tenant.
 */
@Schema
@Data
public class EmulatorProfile {

    private String id;

    private String name;

    /**
     * Business domain of the profile: Energy, Agriculture, Industrial, Lighting, Water, Transportation,
     * Buildings or Smart Cities.
     */
    private String category;

    /**
     * Equipment, Sensor or System.
     */
    private String type;

    private String description;

    private boolean verified = true;

    /**
     * Device profile assigned to the devices created for this emulator.
     */
    private String deviceProfile = "GENERIC";

    /**
     * Model of the emulated equipment, shown next to the device profile in the catalog card (like "ST-50K" in PE).
     */
    private String model;

    private int intervalSeconds = 10;

    private List<String> scenarios = new ArrayList<>();

    private String defaultScenario;

    private List<EmulatorSignal> signals = new ArrayList<>();

    /**
     * Material icon shown in the catalog card.
     */
    private String icon = "memory";

    private String color = "#305680";
}
