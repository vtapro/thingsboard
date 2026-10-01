// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Container stored in AdminSettings under the "emulators" key.
 */
@Schema
@Data
public class EmulatorSettings {

    private List<EmulatorInstance> instances = new ArrayList<>();
}
