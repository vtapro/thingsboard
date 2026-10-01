// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.rbac.RbacEntityGroup;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;

public interface EntityGroupService {

    RbacEntityGroupSettings getEntityGroupSettings(TenantId tenantId);

    RbacEntityGroupSettings saveEntityGroupSettings(TenantId tenantId, RbacEntityGroupSettings settings);

    /**
     * Creates or updates a single group. The other groups of the tenant are not touched.
     */
    RbacEntityGroupSettings saveEntityGroup(TenantId tenantId, RbacEntityGroup group);

    /**
     * Deletes a single group. The other groups of the tenant are not touched.
     */
    RbacEntityGroupSettings deleteEntityGroup(TenantId tenantId, String groupId);

    /**
     * Keeps the default user groups in sync with the authority of the user (Tenant Administrators for a
     * TENANT_ADMIN, Tenant Users for a CUSTOMER_USER), like the default groups of ThingsBoard PE.
     */
    void syncUserGroups(TenantId tenantId, User user);

    /**
     * Removes a deleted user from every user group of the tenant.
     */
    void removeUserFromGroups(TenantId tenantId, UserId userId);

}
