// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;
import org.thingsboard.server.common.data.security.Authority;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultEntityGroupService implements EntityGroupService {

    public static final String ENTITY_GROUPS_SETTINGS_KEY = "entityGroups";
    public static final String ALL_GROUP_NAME = "All";
    /**
     * Default user groups of PE: they follow the authority of the user, so a role may grant permissions to the
     * administrators of the tenant or to its users without listing the users one by one.
     */
    public static final String TENANT_ADMINS_GROUP_NAME = "Tenant Administrators";
    public static final String TENANT_USERS_GROUP_NAME = "Tenant Users";
    public static final List<String> DEFAULT_USER_GROUP_NAMES =
            List.of(TENANT_ADMINS_GROUP_NAME, TENANT_USERS_GROUP_NAME);

    /**
     * True when the membership of the group is derived from the platform instead of being maintained by the tenant
     * administrator: the two default user groups follow the authority of the user, so the "Manage owner and groups"
     * dialog shows them but never changes them.
     */
    public static boolean isAuthorityManagedUserGroup(RbacEntityGroup group) {
        return group != null && "USER".equals(group.getEntityType())
                && DEFAULT_USER_GROUP_NAMES.contains(group.getName());
    }

    /**
     * Entity types that always have an "All" group: the classic PE ones plus the members of this fork
     * (customers and users), so the Groups tab of the Users / Customers pages always shows "All" too.
     */
    private static final List<String> DEFAULT_GROUP_ENTITY_TYPES =
            List.of("DEVICE", "ASSET", "ENTITY_VIEW", "CUSTOMER", "USER");

    private final AdminSettingsService adminSettingsService;
    private final TenantSettingsLocks locks = new TenantSettingsLocks();

    @Override
    public RbacEntityGroupSettings getEntityGroupSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ENTITY_GROUPS_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return ensureDefaultGroups(tenantId, new RbacEntityGroupSettings());
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
     * it matches every entity of the type and its membership is not editable.
     *
     * <p>The group is completed in memory only (reading the settings never writes them) and its id is derived from
     * the tenant and the entity type, so every read returns the same id and the scoped permissions that reference
     * the "All" group keep working. The group is persisted by the next save of the settings.
     */
    private RbacEntityGroupSettings ensureDefaultGroups(TenantId tenantId, RbacEntityGroupSettings settings) {
        if (settings.getGroups() == null) {
            settings.setGroups(new ArrayList<>());
        }
        for (String entityType : DEFAULT_GROUP_ENTITY_TYPES) {
            boolean exists = settings.getGroups().stream()
                    .anyMatch(group -> entityType.equals(group.getEntityType()) && group.isAllGroup());
            if (!exists) {
                RbacEntityGroup group = new RbacEntityGroup();
                group.setId(allGroupId(tenantId, entityType));
                group.setName(ALL_GROUP_NAME);
                group.setEntityType(entityType);
                group.setAllGroup(true);
                group.setDescription("All " + entityType.toLowerCase().replace('_', ' ') + "s of the tenant");
                group.setCreatedTime(System.currentTimeMillis());
                settings.getGroups().add(group);
            }
        }
        for (String name : DEFAULT_USER_GROUP_NAMES) {
            boolean exists = settings.getGroups().stream()
                    .anyMatch(group -> "USER".equals(group.getEntityType()) && name.equals(group.getName()));
            if (!exists) {
                RbacEntityGroup group = new RbacEntityGroup();
                group.setId(defaultUserGroupId(tenantId, name));
                group.setName(name);
                group.setEntityType("USER");
                group.setDescription(TENANT_ADMINS_GROUP_NAME.equals(name)
                        ? "Tenant administrators of the tenant (kept in sync by the platform)"
                        : "Users of the tenant (kept in sync by the platform)");
                group.setCreatedTime(System.currentTimeMillis());
                settings.getGroups().add(group);
            }
        }
        return settings;
    }

    /** Stable id of a default user group, so every read returns the same id (see {@link #allGroupId}). */
    public static String defaultUserGroupId(TenantId tenantId, String name) {
        return UUID.nameUUIDFromBytes(("defaultUserGroup:" + tenantId.getId() + ":" + name)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public synchronized void syncUserGroups(TenantId tenantId, User user) {
        if (user == null || user.getId() == null || user.getAuthority() == null) {
            return;
        }
        if (!Authority.TENANT_ADMIN.equals(user.getAuthority()) && !Authority.CUSTOMER_USER.equals(user.getAuthority())) {
            return;
        }
        RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
        String entity = user.getId().getId().toString();
        boolean changed = updateMembership(settings, entity, user.getAuthority());
        if (changed) {
            saveEntityGroupSettings(tenantId, settings);
        }
    }

    @Override
    public synchronized void removeUserFromGroups(TenantId tenantId, UserId userId) {
        if (userId == null) {
            return;
        }
        RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
        String entity = userId.getId().toString();
        boolean changed = false;
        for (RbacEntityGroup group : settings.getGroups()) {
            if ("USER".equals(group.getEntityType()) && group.getEntityIds() != null
                    && group.getEntityIds().remove(entity)) {
                changed = true;
            }
        }
        if (changed) {
            saveEntityGroupSettings(tenantId, settings);
        }
    }

    /**
     * A tenant administrator belongs to "Tenant Administrators", a customer user to "Tenant Users"; the other
     * default group is left by the user. Returns true when the membership changed.
     */
    private boolean updateMembership(RbacEntityGroupSettings settings, String entity, Authority authority) {
        boolean changed = false;
        String expectedGroup = Authority.TENANT_ADMIN.equals(authority)
                ? TENANT_ADMINS_GROUP_NAME : TENANT_USERS_GROUP_NAME;
        for (RbacEntityGroup group : settings.getGroups()) {
            if (!"USER".equals(group.getEntityType()) || !DEFAULT_USER_GROUP_NAMES.contains(group.getName())) {
                continue;
            }
            if (group.getEntityIds() == null) {
                group.setEntityIds(new ArrayList<>());
            }
            boolean expected = expectedGroup.equals(group.getName());
            boolean member = group.getEntityIds().contains(entity);
            if (expected && !member) {
                group.getEntityIds().add(entity);
                changed = true;
            } else if (!expected && member) {
                group.getEntityIds().remove(entity);
                changed = true;
            }
        }
        return changed;
    }

    /** Stable id of the "All" group of an entity type, so the id survives the reads that do not persist it. */
    public static String allGroupId(TenantId tenantId, String entityType) {
        return UUID.nameUUIDFromBytes((tenantId.getId() + ":all:" + entityType).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public RbacEntityGroupSettings saveEntityGroupSettings(TenantId tenantId, RbacEntityGroupSettings settings) {
        synchronized (locks.lockFor(tenantId)) {
            RbacEntityGroupSettings toSave = settings != null ? settings : new RbacEntityGroupSettings();
            AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ENTITY_GROUPS_SETTINGS_KEY);
            if (adminSettings == null) {
                adminSettings = new AdminSettings();
                adminSettings.setTenantId(tenantId);
                adminSettings.setKey(ENTITY_GROUPS_SETTINGS_KEY);
            }
            adminSettings.setJsonValue(JacksonUtil.valueToTree(toSave));
            AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacEntityGroupSettings.class);
        }
    }

    @Override
    public RbacEntityGroupSettings saveEntityGroup(TenantId tenantId, RbacEntityGroup group) {
        synchronized (locks.lockFor(tenantId)) {
            RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
            List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
            groups.removeIf(existing -> Objects.equals(existing.getId(), group.getId()));
            groups.add(group);
            settings.setGroups(groups);
            return saveEntityGroupSettings(tenantId, settings);
        }
    }

    @Override
    public RbacEntityGroupSettings deleteEntityGroup(TenantId tenantId, String groupId) {
        synchronized (locks.lockFor(tenantId)) {
            RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
            List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
            groups.removeIf(existing -> Objects.equals(existing.getId(), groupId));
            settings.setGroups(groups);
            return saveEntityGroupSettings(tenantId, settings);
        }
    }

}
