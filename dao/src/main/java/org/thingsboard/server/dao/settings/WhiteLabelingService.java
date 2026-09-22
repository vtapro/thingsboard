// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.whiteLabeling.WhiteLabelingSettings;
import org.thingsboard.server.common.data.id.TenantId;

public interface WhiteLabelingService {

    WhiteLabelingSettings getWhiteLabelingSettings();

    WhiteLabelingSettings getWhiteLabelingSettings(TenantId tenantId);

    WhiteLabelingSettings saveWhiteLabelingSettings(WhiteLabelingSettings settings);

    WhiteLabelingSettings saveWhiteLabelingSettings(TenantId tenantId, WhiteLabelingSettings settings);

}
