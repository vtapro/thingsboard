// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;
import org.thingsboard.server.common.data.rbac.RbacUserGroup;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class DefaultRoleService implements RoleService {

    public static final String ROLES_SETTINGS_KEY = "roles";

    private static final String EFFECTIVE_ROLE_ID = "effective";

    /**
     * The two profiles of the customer users of PE, pre-configured for the tenant: a "Customer Administrator" may
     * manage the devices, assets, entity views, dashboards, users and sub-customers of its own customer, a plain
     * "Customer User" only reads them. The two default user groups of the same name carry the role
     * (see DefaultUserGroupService), so the tenant administrator switches the profile of a user by moving it to the
     * matching group in the "Manage owner and groups" dialog.
     */
    public static final String CUSTOMER_ADMIN_ROLE_NAME = "Customer Administrator";
    public static final String CUSTOMER_USER_ROLE_NAME = "Customer User";
    /**
     * Version of the pre-configured permissions of the two profiles. It is stored on the role, so a tenant that was
     * created with an older version gets the new default permissions merged in once (see ensureDefaultRoles), while
     * the later changes of the tenant administrator are kept.
     *
     * <p>1: the initial profiles; 2: the plain customer user may claim the devices of the provider (CLAIM_DEVICES).</p>
     */
    private static final int PROFILE_DEFAULT_VERSION = 2;

    private final AdminSettingsService adminSettingsService;
    private final UserGroupService userGroupService;
    private final TenantSettingsLocks locks = new TenantSettingsLocks();

    @Override
    public RbacRoleSettings getRoleSettings(TenantId tenantId) {
        return ensureDefaultRoles(tenantId, readRoleSettings(tenantId));
    }

    private RbacRoleSettings readRoleSettings(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ROLES_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacRoleSettings();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER
                    .convertValue(adminSettings.getJsonValue(), RbacRoleSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load roles settings!", e);
        }
    }

    /** Writes the settings of the tenant; the caller already holds the tenant lock. */
    private RbacRoleSettings writeRoleSettings(TenantId tenantId, RbacRoleSettings settings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, ROLES_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(ROLES_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(settings));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacRoleSettings.class);
    }

    /**
     * The two profiles of the customer users are always available: they are completed in memory only (like the
     * default entity groups) and their id is derived from the tenant and the name, so the default user groups that
     * reference them keep working and the tenant administrator sees exactly what a customer user may do.
     */
    private RbacRoleSettings ensureDefaultRoles(TenantId tenantId, RbacRoleSettings settings) {
        if (settings.getRoles() == null) {
            settings.setRoles(new ArrayList<>());
        }
        for (RbacRole role : List.of(customerAdminRole(tenantId), customerUserRole(tenantId))) {
            RbacRole stored = settings.getRoles().stream()
                    .filter(candidate -> role.getId().equals(candidate.getId())
                            || (!candidate.isSystem() && role.getName().equals(candidate.getName())))
                    .findFirst().orElse(null);
            if (stored == null) {
                settings.getRoles().add(role);
            } else {
                // adopt the stored role as the system role of the profile: the tenant administrator may have tuned
                // its permissions (they are kept), but its name and its existence are owned by the platform
                stored.setSystem(true);
                stored.setName(role.getName());
                if (stored.getDefaultVersion() < PROFILE_DEFAULT_VERSION) {
                    // merge the new platform defaults once, then remember that the role is up to date
                    mergeDefaultPermissions(role, stored);
                    stored.setDefaultVersion(PROFILE_DEFAULT_VERSION);
                }
            }
        }
        return settings;
    }

    /**
     * Adds the operations of the platform defaults that the stored role does not have yet (the administrator may have
     * removed some of them later, the version prevents us from adding them again).
     */
    private static void mergeDefaultPermissions(RbacRole source, RbacRole target) {
        if (source.getPermissions() == null) {
            return;
        }
        if (target.getPermissions() == null) {
            target.setPermissions(new LinkedHashMap<>());
        }
        source.getPermissions().forEach((resource, operations) -> {
            List<String> merged = target.getPermissions().computeIfAbsent(resource, r -> new ArrayList<>());
            for (String operation : operations) {
                if (!merged.contains(operation)) {
                    merged.add(operation);
                }
            }
        });
    }

    /** Stable id of a default role, so the default user groups may reference it before it is persisted. */
    public static String defaultRoleId(TenantId tenantId, String name) {
        return UUID.nameUUIDFromBytes(("defaultRole:" + tenantId.getId() + ":" + name)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private RbacRole customerAdminRole(TenantId tenantId) {
        List<String> manage = List.of("CREATE", "READ", "WRITE", "DELETE");
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put("DEVICE", manage);
        permissions.put("ASSET", manage);
        permissions.put("ENTITY_VIEW", manage);
        permissions.put("DASHBOARD", manage);
        permissions.put("USER", manage);
        permissions.put("CUSTOMER", List.of("CREATE", "READ", "WRITE"));
        permissions.put("ALARM", List.of("READ", "WRITE"));
        RbacRole role = new RbacRole();
        role.setId(defaultRoleId(tenantId, CUSTOMER_ADMIN_ROLE_NAME));
        role.setName(CUSTOMER_ADMIN_ROLE_NAME);
        role.setPermissions(permissions);
        role.setSystem(true);
        role.setDefaultVersion(PROFILE_DEFAULT_VERSION);
        // the administrator manages its own customer and, when the tenant administrator declares them in the
        // Customer hierarchy tab, the sub-customers of that customer
        role.setOwnCustomerOnly(true);
        return role;
    }

    private RbacRole customerUserRole(TenantId tenantId) {
        // The plain user only reads the telemetry of its customer, but it may claim the devices that the provider
        // pre-registered for it (the device sends a claim request with a secret, the user enters it in the UI). A
        // role that lists an operation outside the four basic ones is a "detailed" role, so every auxiliary operation
        // the dashboards of the user need is listed explicitly.
        List<String> read = List.of("READ", "READ_ATTRIBUTES", "READ_TELEMETRY");
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put("DEVICE", List.of("READ", "READ_ATTRIBUTES", "READ_TELEMETRY", "CLAIM_DEVICES"));
        permissions.put("ASSET", read);
        permissions.put("ENTITY_VIEW", read);
        permissions.put("DASHBOARD", read);
        permissions.put("CUSTOMER", read);
        // acknowledging an alarm is part of the day to day work of a user that may only read the telemetry
        permissions.put("ALARM", List.of("READ", "WRITE"));
        RbacRole role = new RbacRole();
        role.setId(defaultRoleId(tenantId, CUSTOMER_USER_ROLE_NAME));
        role.setName(CUSTOMER_USER_ROLE_NAME);
        role.setPermissions(permissions);
        role.setSystem(true);
        role.setDefaultVersion(PROFILE_DEFAULT_VERSION);
        return role;
    }

    @Override
    public RbacRoleSettings saveRoleSettings(TenantId tenantId, RbacRoleSettings settings) {
        synchronized (locks.lockFor(tenantId)) {
            RbacRoleSettings incoming = settings != null ? settings : new RbacRoleSettings();
            // the client (WEB UI) does not send the platform metadata of a role back: carry it over, otherwise the
            // platform would merge the default permissions again (and undo the tuning of the administrator)
            RbacRoleSettings stored = readRoleSettings(tenantId);
            if (incoming.getRoles() != null && stored.getRoles() != null) {
                for (RbacRole role : incoming.getRoles()) {
                    for (RbacRole existing : stored.getRoles()) {
                        if (role.getId() != null && role.getId().equals(existing.getId())) {
                            role.setSystem(existing.isSystem());
                            if (role.getDefaultVersion() < existing.getDefaultVersion()) {
                                role.setDefaultVersion(existing.getDefaultVersion());
                            }
                            break;
                        }
                    }
                }
            }
            // the roles of the two profiles of a customer user always exist and keep their name: the platform owns
            // them (their permissions stay editable)
            return writeRoleSettings(tenantId, ensureDefaultRoles(tenantId, incoming));
        }
    }

    @Override
    public RbacRoleSettings updateRoleSettings(TenantId tenantId, Consumer<RbacRoleSettings> update) {
        synchronized (locks.lockFor(tenantId)) {
            // read-modify-write inside the tenant lock, so concurrent changes are not lost
            RbacRoleSettings settings = ensureDefaultRoles(tenantId, readRoleSettings(tenantId));
            update.accept(settings);
            return writeRoleSettings(tenantId, settings);
        }
    }

    @Override
    public List<RbacRole> getEffectiveRoles(TenantId tenantId, String userId) {
        List<RbacRole> roles = getRoleSettings(tenantId).getRoles();
        if (roles == null || roles.isEmpty() || userId == null) {
            return List.of();
        }
        Set<String> roleIds = new HashSet<>();
        for (RbacRole role : roles) {
            if (role.getUserIds() != null && role.getUserIds().contains(userId)) {
                roleIds.add(role.getId());
            }
        }
        for (RbacUserGroup userGroup : getSafeUserGroups(tenantId)) {
            if (userGroup.getRoleIds() != null && userGroup.getUserIds() != null
                    && userGroup.getUserIds().contains(userId)) {
                roleIds.addAll(userGroup.getRoleIds());
            }
        }
        List<RbacRole> effectiveRoles = new ArrayList<>();
        for (RbacRole role : roles) {
            if (roleIds.contains(role.getId())) {
                effectiveRoles.add(role);
            }
        }
        return effectiveRoles;
    }

    @Override
    public RbacRole getEffectiveRole(TenantId tenantId, String userId) {
        List<RbacRole> effectiveRoles = getEffectiveRoles(tenantId, userId);
        if (effectiveRoles.isEmpty()) {
            return null;
        }
        RbacRole effective = new RbacRole();
        effective.setId(EFFECTIVE_ROLE_ID);
        effective.setName(EFFECTIVE_ROLE_ID);
        for (RbacRole role : effectiveRoles) {
            mergePermissions(role, effective);
        }
        return effective;
    }

    private List<RbacUserGroup> getSafeUserGroups(TenantId tenantId) {
        List<RbacUserGroup> groups = userGroupService.getUserGroupSettings(tenantId).getGroups();
        return groups != null ? groups : List.of();
    }

    /**
     * Roles are additive: the effective permissions of a user are the union of the permissions of all its roles.
     *
     * <p>The "own entities only" flag is a restriction, not a permission: when one of the roles restricts a resource,
     * the effective role restricts it as well. A restriction always wins over the grants of the other roles, so
     * adding a role to a user can never widen an explicit restriction.
     */
    private static void mergePermissions(RbacRole source, RbacRole target) {
        if (source.getPermissions() != null) {
            source.getPermissions().forEach((resource, operations) -> {
                List<String> merged = target.getPermissions().computeIfAbsent(resource, r -> new ArrayList<>());
                for (String operation : operations) {
                    if (!merged.contains(operation)) {
                        merged.add(operation);
                    }
                }
            });
        }
        if (source.getScopedPermissions() != null) {
            source.getScopedPermissions().forEach((resource, byOperation) ->
                    byOperation.forEach((operation, groupIds) -> {
                        List<String> merged = target.getScopedPermissions()
                                .computeIfAbsent(resource, r -> new HashMap<>())
                                .computeIfAbsent(operation, o -> new ArrayList<>());
                        for (String groupId : groupIds) {
                            if (!merged.contains(groupId)) {
                                merged.add(groupId);
                            }
                        }
                    }));
        }
        if (source.isOwnCustomerOnly()) {
            target.setOwnCustomerOnly(true);
        }
        if (source.getOwnOnly() != null) {
            source.getOwnOnly().forEach((resource, ownOnly) -> {
                if (Boolean.TRUE.equals(ownOnly)) {
                    target.getOwnOnly().put(resource, true);
                }
            });
        }
    }

}
