// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;

import java.util.List;

public interface RoleService {

    RbacRoleSettings getRoleSettings(TenantId tenantId);

    RbacRoleSettings saveRoleSettings(TenantId tenantId, RbacRoleSettings settings);

    /**
     * Returns the custom roles that apply to the user: the roles assigned directly to the user and the roles
     * inherited from the user groups the user belongs to.
     */
    List<RbacRole> getEffectiveRoles(TenantId tenantId, String userId);

    /**
     * Returns a single role that is the union of the {@link #getEffectiveRoles(TenantId, String) effective roles},
     * or {@code null} when no custom role applies to the user.
     */
    RbacRole getEffectiveRole(TenantId tenantId, String userId);

}
