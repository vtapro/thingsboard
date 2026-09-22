// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.menu.CustomMenuSettings;

public interface CustomMenuService {

    CustomMenuSettings getCustomMenuSettings(TenantId tenantId);

    CustomMenuSettings saveCustomMenuSettings(TenantId tenantId, CustomMenuSettings settings);

}

