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
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class DefaultEntityGroupService implements EntityGroupService {

    public static final String ENTITY_GROUPS_SETTINGS_KEY = "entityGroups";
    public static final String ALL_GROUP_NAME = "All";

    private static final String TENANT_ADMINS_GROUP_NAME = "Tenant Administrators";
    private static final String TENANT_USERS_GROUP_NAME = "Tenant Users";

    /**
     * User groups that used to be created by the platform and follow the authority of the user. ThingsBoard PE does
     * not create them (the profiles of a customer user below are the way to grant permissions to a set of users), so
     * they are removed from the settings of a tenant that still stores them.
     */
    public static final List<String> LEGACY_AUTHORITY_GROUP_NAMES =
            List.of(TENANT_ADMINS_GROUP_NAME, TENANT_USERS_GROUP_NAME);
    /**
     * The two profiles of a customer user of PE: an administrator of the customer may create and manage the devices,
     * assets, entity views and dashboards of the customer (and manage its users and sub-customers), a plain user only
     * reads them. The membership of these two groups is maintained by the tenant administrator in the
     * "Manage owner and groups" dialog (their roles are pre-configured, see DefaultRoleService).
     */
    public static final String CUSTOMER_ADMINS_GROUP_NAME = "Customer Administrators";
    public static final String CUSTOMER_USERS_GROUP_NAME = "Customer Users";
    public static final List<String> CUSTOMER_PROFILE_GROUP_NAMES =
            List.of(CUSTOMER_ADMINS_GROUP_NAME, CUSTOMER_USERS_GROUP_NAME);

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
        // the groups that followed the authority of the user are not part of the model of PE any more
        settings.getGroups().removeIf(group -> "USER".equals(group.getEntityType())
                && LEGACY_AUTHORITY_GROUP_NAMES.contains(group.getName()));
        // the two profiles of the customer users of PE
        for (String name : CUSTOMER_PROFILE_GROUP_NAMES) {
            boolean exists = settings.getGroups().stream()
                    .anyMatch(group -> "USER".equals(group.getEntityType()) && name.equals(group.getName()));
            if (!exists) {
                settings.getGroups().add(defaultUserGroup(tenantId, name,
                        CUSTOMER_ADMINS_GROUP_NAME.equals(name)
                                ? "Customer users that manage the devices, assets, entity views and users of their "
                                        + "customer (permissions of the role 'Customer Administrator')"
                                : "Customer users that may only read the data of their customer "
                                        + "(permissions of the role 'Customer User')"));
            }
        }
        return settings;
    }

    private RbacEntityGroup defaultUserGroup(TenantId tenantId, String name, String description) {
        RbacEntityGroup group = new RbacEntityGroup();
        group.setId(defaultUserGroupId(tenantId, name));
        group.setName(name);
        group.setEntityType("USER");
        group.setDescription(description);
        group.setCreatedTime(System.currentTimeMillis());
        return group;
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
        String entity = user.getId().getId().toString();
        updateEntityGroupSettings(tenantId, settings -> updateMembership(settings, entity, user.getAuthority()));
    }

    @Override
    public synchronized void removeUserFromGroups(TenantId tenantId, UserId userId) {
        if (userId == null) {
            return;
        }
        String entity = userId.getId().toString();
        updateEntityGroupSettings(tenantId, settings -> {
            for (RbacEntityGroup group : settings.getGroups()) {
                if ("USER".equals(group.getEntityType()) && group.getEntityIds() != null) {
                    group.getEntityIds().remove(entity);
                }
            }
        });
    }

    /**
     * A customer user always has one of the two profiles of a customer user. Returns true when the membership
     * changed.
     */
    private boolean updateMembership(RbacEntityGroupSettings settings, String entity, Authority authority) {
        boolean changed = false;
        if (Authority.CUSTOMER_USER.equals(authority)) {
            // A new customer user is a plain user; the tenant administrator promotes it to the administrator of its
            // customer by moving it to the "Customer Administrators" group in the dialog.
            changed |= ensureCustomerProfile(settings, entity);
        }
        return changed;
    }

    /**
     * A customer user always has one of the two profiles: when it belongs to neither "Customer Administrators" nor
     * "Customer Users" it is a plain user. Returns true when the membership changed.
     */
    private boolean ensureCustomerProfile(RbacEntityGroupSettings settings, String entity) {
        boolean memberOfAnyProfile = false;
        RbacEntityGroup customersUsers = null;
        for (RbacEntityGroup group : settings.getGroups()) {
            if (!"USER".equals(group.getEntityType()) || !CUSTOMER_PROFILE_GROUP_NAMES.contains(group.getName())) {
                continue;
            }
            if (group.getEntityIds() != null && group.getEntityIds().contains(entity)) {
                memberOfAnyProfile = true;
            }
            if (CUSTOMER_USERS_GROUP_NAME.equals(group.getName())) {
                customersUsers = group;
            }
        }
        if (memberOfAnyProfile || customersUsers == null) {
            return false;
        }
        if (customersUsers.getEntityIds() == null) {
            customersUsers.setEntityIds(new ArrayList<>());
        }
        customersUsers.getEntityIds().add(entity);
        return true;
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
            return updateEntityGroupSettings(tenantId, settings -> {
                List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
                groups.removeIf(existing -> Objects.equals(existing.getId(), group.getId()));
                groups.add(group);
                settings.setGroups(groups);
            });
        }
    }

    @Override
    public RbacEntityGroupSettings deleteEntityGroup(TenantId tenantId, String groupId) {
        synchronized (locks.lockFor(tenantId)) {
            return updateEntityGroupSettings(tenantId, settings -> {
                List<RbacEntityGroup> groups = new ArrayList<>(settings.getGroups() != null ? settings.getGroups() : List.of());
                groups.removeIf(existing -> Objects.equals(existing.getId(), groupId));
                settings.setGroups(groups);
            });
        }
    }

    @Override
    public RbacEntityGroupSettings updateEntityGroupSettings(TenantId tenantId, Consumer<RbacEntityGroupSettings> update) {
        synchronized (locks.lockFor(tenantId)) {
            // read-modify-write inside the tenant lock, so two administrators that edit the groups (or the members of
            // two different entities) at the same time do not overwrite each other
            RbacEntityGroupSettings settings = getEntityGroupSettings(tenantId);
            update.accept(settings);
            return saveEntityGroupSettings(tenantId, settings);
        }
    }

}
