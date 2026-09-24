// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DefaultEntityGroupService implements EntityGroupService {

    public static final String ENTITY_GROUPS_SETTINGS_KEY = "entityGroups";

    private final AdminSettingsService adminSettingsService;

    @Override
    public RbacEntityGroupSettings getEntityGroupSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ENTITY_GROUPS_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacEntityGroupSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), RbacEntityGroupSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load entity group settings!", e);
        }
    }

    @Override
    public RbacEntityGroupSettings saveEntityGroupSettings(TenantId tenantId, RbacEntityGroupSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ENTITY_GROUPS_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(ENTITY_GROUPS_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacEntityGroupSettings.class);
    }

    @Override
    public RbacEntityGroupSettings saveEntityGroup(TenantId tenantId, RbacEntityGroup group) {
        RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
        List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
        groups.removeIf(existing -> Objects.equals(existing.getId(), group.getId()));
        groups.add(group);
        settings.setGroups(groups);
        return saveEntityGroupSettings(tenantId, settings);
    }

    @Override
    public RbacEntityGroupSettings deleteEntityGroup(TenantId tenantId, String groupId) {
        RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
        List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
        groups.removeIf(existing -> Objects.equals(existing.getId(), groupId));
        settings.setGroups(groups);
        return saveEntityGroupSettings(tenantId, settings);
    }

}
