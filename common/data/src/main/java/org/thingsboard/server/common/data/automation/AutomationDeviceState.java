// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.automation;

import lombok.Data;

/**
 * Device connectivity trigger, e.g. "when the device goes offline".
 */
@Data
public class AutomationDeviceState {

    /**
     * ONLINE or OFFLINE.
     */
    private String state = "OFFLINE";
}
