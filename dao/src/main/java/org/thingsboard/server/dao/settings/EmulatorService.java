// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.emulator.EmulatorSettings;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.List;

/**
 * Emulators of a tenant, stored as a JSON document in AdminSettings (key "emulators").
 */
public interface EmulatorService {

    EmulatorSettings getEmulatorSettings(TenantId tenantId);

    List<EmulatorInstance> getEmulators(TenantId tenantId);

    EmulatorInstance saveEmulator(TenantId tenantId, EmulatorInstance instance);

    void deleteEmulator(TenantId tenantId, String instanceId);
}
