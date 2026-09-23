// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacEntityGroupSettings;

public interface EntityGroupService {

    RbacEntityGroupSettings getEntityGroupSettings(TenantId tenantId);

    RbacEntityGroupSettings saveEntityGroupSettings(TenantId tenantId, RbacEntityGroupSettings settings);

}

