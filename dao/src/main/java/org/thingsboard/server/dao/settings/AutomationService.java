// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.automation.AutomationRules;
import org.thingsboard.server.common.data.id.TenantId;

public interface AutomationService {

    AutomationRules getAutomationRules(TenantId tenantId);

    AutomationRules saveAutomationRules(TenantId tenantId, AutomationRules rules);

}
