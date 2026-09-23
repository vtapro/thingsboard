// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacCustomerHierarchy;

import java.util.Set;

public interface CustomerHierarchyService {

    RbacCustomerHierarchy getCustomerHierarchy(TenantId tenantId);

    RbacCustomerHierarchy saveCustomerHierarchy(TenantId tenantId, RbacCustomerHierarchy hierarchy);

    /**
     * Returns the customer id together with all its descendants (children, grandchildren, ...).
     */
    Set<String> getCustomerSubtree(TenantId tenantId, String customerId);

}

