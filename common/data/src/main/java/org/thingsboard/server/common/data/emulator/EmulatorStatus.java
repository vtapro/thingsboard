// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

/**
 * Runtime state of an emulator: only a RUNNING emulator publishes telemetry.
 */
public enum EmulatorStatus {
    STOPPED,
    RUNNING,
    PAUSED
}
