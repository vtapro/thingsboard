// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;

/**
 * Striped locks that serialize the read-modify-write cycles on the JSON settings documents of a tenant
 * (roles, entity groups, user groups, shares). The settings are stored as a single document per tenant, so two
 * concurrent saves would otherwise lose the change of the other one.
 *
 * <p>The lock is held by one server only: a cluster needs a single writer or a version check on the document.
 */
class TenantSettingsLocks {

    private static final int STRIPES = 64;

    private final Object[] locks = new Object[STRIPES];

    TenantSettingsLocks() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    Object lockFor(TenantId tenantId) {
        int index = tenantId == null || tenantId.getId() == null
                ? 0
                : Math.floorMod(tenantId.getId().hashCode(), STRIPES);
        return locks[index];
    }

}
