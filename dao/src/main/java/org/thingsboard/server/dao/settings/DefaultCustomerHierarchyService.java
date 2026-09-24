// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rbac.RbacCustomerHierarchy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultCustomerHierarchyService implements CustomerHierarchyService {

    public static final String CUSTOMER_HIERARCHY_SETTINGS_KEY = "customerHierarchy";

    private final AdminSettingsService adminSettingsService;

    @Override
    public RbacCustomerHierarchy getCustomerHierarchy(TenantId tenantId) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, CUSTOMER_HIERARCHY_SETTINGS_KEY);
        if (adminSettings == null || adminSettings.getJsonValue() == null) {
            return new RbacCustomerHierarchy();
        }
        try {
            return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(adminSettings.getJsonValue(), RbacCustomerHierarchy.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load customer hierarchy!", e);
        }
    }

    @Override
    public RbacCustomerHierarchy saveCustomerHierarchy(TenantId tenantId, RbacCustomerHierarchy hierarchy) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByTenantIdAndKey(tenantId, CUSTOMER_HIERARCHY_SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(tenantId);
            adminSettings.setKey(CUSTOMER_HIERARCHY_SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(hierarchy));
        AdminSettings saved = adminSettingsService.saveAdminSettings(tenantId, adminSettings);
        return JacksonUtil.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(saved.getJsonValue(), RbacCustomerHierarchy.class);
    }

    @Override
    public Set<String> getCustomerSubtree(TenantId tenantId, String customerId) {
        Map<String, String> parents = getCustomerHierarchy(tenantId).getParents();
        Set<String> result = new HashSet<>();
        if (customerId == null) {
            return result;
        }
        result.add(customerId);
        boolean added = true;
        while (added) {
            added = false;
            for (Map.Entry<String, String> entry : parents.entrySet()) {
                if (result.contains(entry.getValue()) && result.add(entry.getKey())) {
                    added = true;
                }
            }
        }
        return result;
    }

}

