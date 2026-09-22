// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import org.thingsboard.server.common.data.id.TenantId;

import java.util.Map;

public interface CustomTranslationService {

    Map<String, Map<String, String>> getCustomTranslations(TenantId tenantId);

    Map<String, String> getCustomTranslations(TenantId tenantId, String locale);

    Map<String, String> saveCustomTranslations(TenantId tenantId, String locale, Map<String, String> translations);

    Map<String, Map<String, String>> deleteCustomTranslations(TenantId tenantId, String locale);

}

