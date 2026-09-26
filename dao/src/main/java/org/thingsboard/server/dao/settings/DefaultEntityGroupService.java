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
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DefaultEntityGroupService implements EntityGroupService {

    public static final String ENTITY_GROUPS_SETTINGS_KEY = "entityGroups";
    public static final String ALL_GROUP_NAME = "All";
    private static final List<String> DEFAULT_GROUP_ENTITY_TYPES = List.of("DEVICE", "ASSET", "ENTITY_VIEW");

    private final AdminSettingsService adminSettingsService;

    @Override
    public RbacEntityGroupSettings getEntityGroupSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ENTITY_GROUPS_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacEntityGroupSettings();
        }
        try {
            return ensureDefaultGroups(tenantId,
                    JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(),
                            RbacEntityGroupSettings.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load entity group settings!", e);
        }
    }

    /**
     * The tenant always has one "All" group per entity type that supports groups (like the default groups of PE):
     * it is created lazily, matches every entity of the type and its membership is not editable.
     */
    private RbacEntityGroupSettings ensureDefaultGroups(TenantId tenantId, RbacEntityGroupSettings settings) {
        if (settings.getGroups() == null) {
            settings.setGroups(new ArrayList<>());
        }
        boolean added = false;
        for (String entityType : DEFAULT_GROUP_ENTITY_TYPES) {
            boolean exists = settings.getGroups().stream()
                    .anyMatch(group -> entityType.equals(group.getEntityType()) && group.isAllGroup());
            if (!exists) {
                RbacEntityGroup group = new RbacEntityGroup();
                group.setId(UUID.randomUUID().toString());
                group.setName(ALL_GROUP_NAME);
                group.setEntityType(entityType);
                group.setAllGroup(true);
                group.setDescription("All " + entityType.toLowerCase().replace('_', ' ') + "s of the tenant");
                group.setCreatedTime(System.currentTimeMillis());
                settings.getGroups().add(group);
                added = true;
            }
        }
        return added ? saveEntityGroupSettings(tenantId, settings) : settings;
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
