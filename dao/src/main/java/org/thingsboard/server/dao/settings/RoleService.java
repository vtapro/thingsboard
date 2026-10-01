// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRole;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;

import java.util.List;
import java.util.function.Consumer;

public interface RoleService {

    RbacRoleSettings getRoleSettings(TenantId tenantId);

    RbacRoleSettings saveRoleSettings(TenantId tenantId, RbacRoleSettings settings);

    /**
     * Applies a change to the role settings of the tenant atomically: the current document is read, updated and
     * written back while the tenant lock is held, so two administrators that change the roles (or the roles of two
     * different users) at the same time do not overwrite each other.
     */
    RbacRoleSettings updateRoleSettings(TenantId tenantId, Consumer<RbacRoleSettings> update);

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
