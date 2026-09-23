// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacRoleSettings;

public interface RoleService {

    RbacRoleSettings getRoleSettings(TenantId tenantId);

    RbacRoleSettings saveRoleSettings(TenantId tenantId, RbacRoleSettings settings);

}

