// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.emulator.EmulatorProfile;
import org.thingsboard.server.common.data.emulator.EmulatorSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Generates the telemetry values of an emulator.
 *
 * <p>The shape of every signal comes from the profile (sine, ramp, random, counter, state), while the scenario
 * modifies it: a "failure" scenario pushes the values to the bad end of their range, a "night"/"dimming" scenario
 * to the low end and a "peak" scenario to the high end. Values are therefore plausible and reproducible for a
 * given emulator, which is what the dashboards and the rule chains need.
 */
@Service
public class EmulatorGenerator {

    private static final double TWO_PI = 2 * Math.PI;

    private static final Set<String> FAULT_WORDS = Set.of(
            "fail", "fault", "blackout", "outage", "overheat", "clog", "breakdown", "loss", "error", "thermal",
            "dry", "low level", "full capacity");
    private static final Set<String> LOW_WORDS = Set.of(
            "off", "dimming", "idle", "night", "maintenance", "sunrise", "unload", "economy", "rainy", "cloudy",
            "free flow", "passing clouds", "sunset");
    private static final Set<String> HIGH_WORDS = Set.of(
            "peak", "busy", "high", "heat", "max", "load", "jam", "demand", "charging", "consumption");

    private final Random random = new Random();

    public ObjectNode generate(EmulatorProfile profile, String scenario, long tick, long ts) {
        ObjectNode values = JacksonUtil.newObjectNode();
        String normalizedScenario = scenario == null ? "" : scenario.toLowerCase(Locale.ROOT);
        boolean fault = containsAny(normalizedScenario, FAULT_WORDS);
        boolean low = containsAny(normalizedScenario, LOW_WORDS);
        boolean high = containsAny(normalizedScenario, HIGH_WORDS);
        for (EmulatorSignal signal : profile.getSignals()) {
            values.set(signal.getKey(), value(signal, tick, fault, low, high));
        }
        return values;
    }

    private JsonNode value(EmulatorSignal signal, long tick, boolean fault, boolean low, boolean high) {
        return switch (signal.getKind()) {
            case EmulatorSignal.KIND_RANDOM -> number(signal,
                    signal.getMin() + signal.span() * random.nextDouble(), fault, low, high);
            case EmulatorSignal.KIND_RAMP -> {
                double progress = (tick % 120) / 120.0;
                yield number(signal, signal.getMin() + signal.span() * progress, fault, low, high);
            }
            case EmulatorSignal.KIND_COUNTER -> {
                // meters and production counters only grow, whatever the scenario is
                yield JsonNodeFactory.instance.numberNode(
                        round(signal.getInitialValue() + tick * signal.getRate(), signal.getDecimals()));
            }
            case EmulatorSignal.KIND_BOOLEAN -> JsonNodeFactory.instance.booleanNode(!(fault || low));
            case EmulatorSignal.KIND_ENUM -> {
                List<String> values = signal.getValues();
                if (values == null || values.isEmpty()) {
                    yield JsonNodeFactory.instance.textNode("");
                }
                int index;
                if (fault) {
                    index = values.size() - 1;
                } else if (low) {
                    index = 0;
                } else {
                    index = (int) ((tick / 15) % values.size());
                }
                yield JsonNodeFactory.instance.textNode(values.get(index));
            }
            default -> { // SINE
                double phase = phase(signal.getKey());
                double period = 90;
                double position = 0.5 + 0.5 * Math.sin(TWO_PI * ((tick % (long) period) / period) + phase);
                yield number(signal, signal.getMin() + signal.span() * position, fault, low, high);
            }
        };
    }

    private JsonNode number(EmulatorSignal signal, double raw, boolean fault, boolean low, boolean high) {
        double value = raw;
        if (fault) {
            // temperature/vibration/pressure reach the alarm end, the other signals stop
            String key = signal.getKey().toLowerCase(Locale.ROOT);
            boolean increases = key.contains("temp") || key.contains("vibration") || key.contains("pressure")
                    || key.contains("humidity") || key.contains("occupancy") || key.contains("aqi")
                    || key.contains("turbidity") || key.contains("consumption");
            value = increases ? signal.getMax() : signal.getMin();
        } else if (low) {
            value = signal.getMin() + signal.span() * 0.15;
        } else if (high) {
            value = signal.getMin() + signal.span() * 0.9;
        } else {
            value += (random.nextDouble() - 0.5) * signal.span() * 0.03;
        }
        return JsonNodeFactory.instance.numberNode(round(value, signal.getDecimals()));
    }

    private double phase(String key) {
        return Math.abs(key.hashCode() % 1000) / 1000.0 * TWO_PI;
    }

    private static double round(double value, int decimals) {
        if (decimals <= 0) {
            return Math.round(value);
        }
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean containsAny(String scenario, Set<String> words) {
        for (String word : words) {
            if (scenario.contains(word)) {
                return true;
            }
        }
        return false;
    }

}
