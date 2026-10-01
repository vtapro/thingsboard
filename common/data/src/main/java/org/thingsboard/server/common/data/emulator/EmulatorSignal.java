// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * One telemetry key of an emulator profile, together with the shape of its values.
 */
@Schema
@Data
public class EmulatorSignal {

    public static final String KIND_SINE = "SINE";
    public static final String KIND_RANDOM = "RANDOM";
    public static final String KIND_RAMP = "RAMP";
    public static final String KIND_BOOLEAN = "BOOLEAN";
    public static final String KIND_ENUM = "ENUM";
    public static final String KIND_COUNTER = "COUNTER";

    private String key;

    private String label;

    private String unit;

    /**
     * SINE, RANDOM, RAMP, BOOLEAN, ENUM or COUNTER (see the constants above).
     */
    private String kind = KIND_SINE;

    private double min;

    private double max = 100;

    private int decimals = 1;

    /**
     * Increment of a COUNTER signal per published value (e.g. 0.02 kWh every 5 seconds).
     */
    private double rate = 1;

    /**
     * Value a COUNTER signal starts from (e.g. an odometer already at 45 000 km), so a demo looks like real life.
     */
    private double initialValue;

    /**
     * Values of an ENUM signal (e.g. "Ready For Charging", "EV Charging").
     */
    private List<String> values;

    public double span() {
        return Math.max(Math.abs(max - min), 0.0001);
    }
}
