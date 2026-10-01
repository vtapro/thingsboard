// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Request of "create emulator": which catalog profile to emulate, the name of the emulator, the initial scenario
 * and whether the dashboard of the domain should be created together with the device.
 */
@Schema
@Data
public class EmulatorCreateRequest {

    private String profileId;

    private String name;

    private String scenario;

    private Integer intervalSeconds;

    private boolean createDashboard = true;
}
