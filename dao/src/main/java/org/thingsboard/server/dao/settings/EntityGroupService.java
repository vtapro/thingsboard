// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
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

}
