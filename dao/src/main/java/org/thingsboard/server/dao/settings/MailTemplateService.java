// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.mail.MailTemplateSettings;

public interface MailTemplateService {

    MailTemplateSettings getMailTemplateSettings(TenantId tenantId);

    MailTemplateSettings saveMailTemplateSettings(TenantId tenantId, MailTemplateSettings settings);

}

