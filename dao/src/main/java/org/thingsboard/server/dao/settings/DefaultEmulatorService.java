// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.emulator.EmulatorSettings;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.List;
import java.util.Objects;

/**
 * Emulators are stored as a JSON document in AdminSettings (key "emulators"), so the fork does not need any
 * database schema change and can still be merged with upstream ThingsBoard.
 */
@Service
@RequiredArgsConstructor
public class DefaultEmulatorService implements EmulatorService {

    public static final String EMULATOR_SETTINGS_KEY = "emulators";

    private final AdminSettingsService adminSettingsService;

    @Override
    public EmulatorSettings getEmulatorSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, EMULATOR_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null || adminSettings.getJsonValue().isNull()) {
            return new EmulatorSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER
                    .convertValue(adminSettings.getJsonValue(), EmulatorSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load emulators!", e);
        }
    }

    @Override
    public List<EmulatorInstance> getEmulators(TenantId tenantId) {
        EmulatorSettings settings = getEmulatorSettings(tenantId);
        return settings.getInstances() != null ? settings.getInstances() : List.of();
    }

    @Override
    public synchronized EmulatorInstance saveEmulator(TenantId tenantId, EmulatorInstance instance) {
        EmulatorSettings settings = getEmulatorSettings(tenantId);
        List<EmulatorInstance> instances = settings.getInstances();
        int index = -1;
        for (int i = 0; i < instances.size(); i++) {
            if (Objects.equals(instances.get(i).getId(), instance.getId())) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            instances.set(index, instance);
        } else {
            instances.add(instance);
        }
        save(tenantId, settings);
        return instance;
    }

    @Override
    public synchronized void deleteEmulator(TenantId tenantId, String instanceId) {
        EmulatorSettings settings = getEmulatorSettings(tenantId);
        settings.getInstances().removeIf(instance -> Objects.equals(instance.getId(), instanceId));
        save(tenantId, settings);
    }

    private void save(TenantId tenantId, EmulatorSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, EMULATOR_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(EMULATOR_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        adminSettingsService.saveAdminSettings(tenantId, adminSettings);
    }

}
