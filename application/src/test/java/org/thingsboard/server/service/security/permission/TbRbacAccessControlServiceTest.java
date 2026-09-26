// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.settings.CustomerHierarchyService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.EntityGroupService;
import org.thingsboard.server.dao.settings.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the custom RBAC enforcement. They cover the two rules that must never be broken:
 * a custom role may only narrow the platform permissions, and it must never break the customer isolation.
 */
public class TbRbacAccessControlServiceTest {

    private static final TenantId TENANT_ID = new TenantId(UUID.randomUUID());
    private static final TenantId OTHER_TENANT_ID = new TenantId(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final CustomerId CHILD_CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final CustomerId OTHER_CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    private final DefaultAccessControlService defaultAccessControlService = mock(DefaultAccessControlService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final EntityGroupService entityGroupService = mock(EntityGroupService.class);
    private final CustomerHierarchyService customerHierarchyService = mock(CustomerHierarchyService.class);
    private final AttributesService attributesService = mock(AttributesService.class);
    private final DeviceService deviceService = mock(DeviceService.class);

    private final TbRbacAccessControlService accessControlService = new TbRbacAccessControlService(
            defaultAccessControlService, roleService, entityGroupService, customerHierarchyService, attributesService, deviceService);

    private SecurityUser customerUser;
    private SecurityUser tenantAdmin;
    private Asset ownCustomerAsset;
    private Asset childCustomerAsset;
    private Asset otherCustomerAsset;

    @BeforeEach
    public void setUp() {
        customerUser = securityUser(Authority.CUSTOMER_USER, CUSTOMER_ID);
        tenantAdmin = securityUser(Authority.TENANT_ADMIN, null);
        ownCustomerAsset = asset(TENANT_ID, CUSTOMER_ID);
        childCustomerAsset = asset(TENANT_ID, CHILD_CUSTOMER_ID);
        otherCustomerAsset = asset(TENANT_ID, OTHER_CUSTOMER_ID);
        Mockito.reset(defaultAccessControlService, roleService, entityGroupService, customerHierarchyService);
    }

    @Test
    public void usesPlatformPermissionsWhenTheUserHasNoCustomRole() throws Exception {
        when(roleService.getEffectiveRole(TENANT_ID, customerUser.getId().getId().toString())).thenReturn(null);
        when(defaultAccessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                ownCustomerAsset.getId(), ownCustomerAsset)).thenReturn(true);

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                ownCustomerAsset.getId(), ownCustomerAsset)).isTrue();
    }

    @Test
    public void deniesWhenTheRoleDoesNotGrantTheOperation() throws Exception {
        givenRole(customerUser, role(Map.of(), Map.of(), false));

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                childCustomerAsset.getId(), childCustomerAsset)).isFalse();
    }

    @Test
    public void ownCustomerOnlyDoesNotGrantOperationsThatTheRoleDoesNotAllow() throws Exception {
        givenRole(customerUser, role(Map.of(), Map.of(), true));
        givenCustomerSubtree(CUSTOMER_ID, Set.of(CUSTOMER_ID.getId().toString(),
                CHILD_CUSTOMER_ID.getId().toString()));

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                childCustomerAsset.getId(), childCustomerAsset)).isFalse();
    }

    @Test
    public void customerUserKeepsAccessToOwnCustomer() throws Exception {
        givenRole(customerUser, role(grants("ASSET", "READ"), Map.of(), false));
        when(defaultAccessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                ownCustomerAsset.getId(), ownCustomerAsset)).thenReturn(true);

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                ownCustomerAsset.getId(), ownCustomerAsset)).isTrue();
    }

    @Test
    public void globalPermissionDoesNotLeakEntitiesOfAnotherCustomer() throws Exception {
        givenRole(customerUser, role(grants("ASSET", "READ"), Map.of(), false));

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                otherCustomerAsset.getId(), otherCustomerAsset)).isFalse();
    }

    @Test
    public void ownCustomerOnlyExtendsAccessToSubCustomers() throws Exception {
        givenRole(customerUser, role(grants("ASSET", "READ"), Map.of(), true));
        givenCustomerSubtree(CUSTOMER_ID, Set.of(CUSTOMER_ID.getId().toString(),
                CHILD_CUSTOMER_ID.getId().toString()));

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                childCustomerAsset.getId(), childCustomerAsset)).isTrue();
    }

    @Test
    public void ownCustomerOnlyDoesNotExtendAccessOutsideOfTheSubtree() throws Exception {
        givenRole(customerUser, role(grants("ASSET", "READ"), Map.of(), true));
        givenCustomerSubtree(CUSTOMER_ID, Set.of(CUSTOMER_ID.getId().toString(),
                CHILD_CUSTOMER_ID.getId().toString()));

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                otherCustomerAsset.getId(), otherCustomerAsset)).isFalse();
    }

    @Test
    public void tenantAdminCannotAccessEntitiesOfAnotherTenant() throws Exception {
        givenRole(tenantAdmin, role(grants("ASSET", "READ"), Map.of(), false));
        Asset foreignAsset = asset(OTHER_TENANT_ID, OTHER_CUSTOMER_ID);

        assertThat(accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ,
                foreignAsset.getId(), foreignAsset)).isFalse();
    }

    @Test
    public void groupScopedPermissionsAreLimitedToTheMembersOfTheGroup() throws Exception {
        RbacEntityGroup group = new RbacEntityGroup();
        group.setId("group-1");
        group.setEntityType("ASSET");
        group.setEntityIds(List.of(childCustomerAsset.getId().getId().toString()));
        RbacEntityGroupSettings groupSettings = new RbacEntityGroupSettings();
        groupSettings.setGroups(List.of(group));
        when(entityGroupService.getEntityGroupSettings(TENANT_ID)).thenReturn(groupSettings);
        givenRole(tenantAdmin, role(Map.of(), Map.of("ASSET", Map.of("READ", List.of("group-1"))), false));

        assertThat(accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ,
                childCustomerAsset.getId(), childCustomerAsset)).isTrue();
        assertThat(accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ,
                ownCustomerAsset.getId(), ownCustomerAsset)).isFalse();
    }

    @Test
    public void entityLessChecksAreLimitedByBothTheRoleAndThePlatformMatrix() throws Exception {
        givenRole(tenantAdmin, role(grants("ASSET", "READ"), Map.of(), false));
        when(defaultAccessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ)).thenReturn(true);
        when(defaultAccessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.WRITE)).thenReturn(true);

        assertThat(accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ)).isTrue();
        assertThat(accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.WRITE)).isFalse();
    }

    @Test
    public void tenantSettingsAreNotReadOnEveryPermissionCheck() throws Exception {
        givenRole(tenantAdmin, role(grants("ASSET", "READ"), Map.of(), false));

        for (int i = 0; i < 5; i++) {
            accessControlService.hasPermission(tenantAdmin, Resource.ASSET, Operation.READ);
        }

        verify(roleService, times(1)).getEffectiveRole(eq(TENANT_ID), any());
    }

    @Test
    public void classicRoleKeepsTheEntityDetailsPageWorking() throws Exception {
        // Role created with the permission matrix of the WEB UI: the four basic operations, scoped to a group.
        givenGroupWithChildCustomerAsset();
        givenRole(customerUser, classicGroupScopedRole());

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ,
                childCustomerAsset.getId(), childCustomerAsset)).isTrue();
        // the device/asset details page also reads the attributes and the telemetry of the entity
        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ_ATTRIBUTES,
                childCustomerAsset.getId(), childCustomerAsset)).isTrue();
        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ_TELEMETRY,
                childCustomerAsset.getId(), childCustomerAsset)).isTrue();
    }

    @Test
    public void credentialsAreNeverDerivedFromRead() throws Exception {
        givenGroupWithChildCustomerAsset();
        givenRole(customerUser, classicGroupScopedRole());

        assertThat(accessControlService.hasPermission(customerUser, Resource.ASSET, Operation.READ_CREDENTIALS,
                childCustomerAsset.getId(), childCustomerAsset)).isFalse();
    }

    private static RbacRole classicGroupScopedRole() {
        return role(Map.of(), Map.of("ASSET", Map.of(
                "CREATE", List.of("group-1"),
                "READ", List.of("group-1"),
                "WRITE", List.of("group-1"),
                "DELETE", List.of("group-1"))), false);
    }

    private void givenGroupWithChildCustomerAsset() {
        RbacEntityGroup group = new RbacEntityGroup();
        group.setId("group-1");
        group.setEntityType("ASSET");
        group.setEntityIds(List.of(childCustomerAsset.getId().getId().toString()));
        RbacEntityGroupSettings groupSettings = new RbacEntityGroupSettings();
        groupSettings.setGroups(List.of(group));
        when(entityGroupService.getEntityGroupSettings(TENANT_ID)).thenReturn(groupSettings);
    }

    private void givenRole(SecurityUser user, RbacRole role) {
        when(roleService.getEffectiveRole(eq(user.getTenantId()), eq(user.getId().getId().toString()))).thenReturn(role);
    }

    private void givenCustomerSubtree(CustomerId customerId, Set<String> subtree) {
        when(customerHierarchyService.getCustomerSubtree(TENANT_ID, customerId.getId().toString())).thenReturn(subtree);
    }

    private static SecurityUser securityUser(Authority authority, CustomerId customerId) {
        SecurityUser user = new SecurityUser(new UserId(UUID.randomUUID()));
        user.setTenantId(TENANT_ID);
        user.setAuthority(authority);
        user.setCustomerId(customerId);
        return user;
    }

    private static Asset asset(TenantId tenantId, CustomerId customerId) {
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));
        asset.setTenantId(tenantId);
        asset.setCustomerId(customerId);
        asset.setName("asset-" + asset.getId().getId());
        return asset;
    }

    private static RbacRole role(Map<String, List<String>> permissions,
                                 Map<String, Map<String, List<String>>> scopedPermissions,
                                 boolean ownCustomerOnly) {
        RbacRole role = new RbacRole();
        role.setId("role-1");
        role.setName("Role 1");
        role.setPermissions(new HashMap<>(permissions));
        role.setScopedPermissions(new HashMap<>(scopedPermissions));
        role.setOwnCustomerOnly(ownCustomerOnly);
        return role;
    }

    private static Map<String, List<String>> grants(String resource, String operation) {
        return Map.of(resource, List.of(operation));
    }

}
