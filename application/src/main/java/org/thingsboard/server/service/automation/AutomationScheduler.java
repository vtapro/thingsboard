// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.automation.AutomationRule;
import org.thingsboard.server.common.data.automation.AutomationRules;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.dao.settings.AutomationService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the time based automations. The scheduler only triggers on the tb-core instance that owns the
 * system partition, so a multi replica deployment never executes a rule twice.
 */
@TbCoreComponent
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationScheduler {

    private static final int TENANT_PAGE_SIZE = 500;
    private static final int MAX_TENANTS = 10000;

    private final AutomationService automationService;
    private final AutomationExecutor automationExecutor;
    private final TenantService tenantService;
    private final PartitionService partitionService;

    @Scheduled(initialDelayString = "${automation.scheduler.initial-delay-ms:30000}",
            fixedDelayString = "${automation.scheduler.interval-ms:15000}")
    public void processDueRules() {
        if (!partitionService.isSystemPartitionMine(ServiceType.TB_CORE)) {
            // another tb-core replica owns the system partition
            return;
        }
        long now = System.currentTimeMillis();
        List<TenantId> tenantIds = listTenantIds();
        for (TenantId tenantId : tenantIds) {
            try {
                processTenant(tenantId, now);
            } catch (Exception e) {
                log.warn("[{}] Failed to process automation rules", tenantId, e);
            }
        }
    }

    private void processTenant(TenantId tenantId, long now) {
        AutomationRules rules = automationService.getAutomationRules(tenantId);
        if (rules.getRules() == null || rules.getRules().isEmpty()) {
            return;
        }
        boolean changed = false;
        for (AutomationRule rule : rules.getRules()) {
            if (rule.getSchedule() == null) {
                continue;
            }
            if (rule.getNextRunTs() == null) {
                rule.setNextRunTs(AutomationScheduleCalculator.nextRunTs(rule.getSchedule(), now));
                changed = true;
                continue;
            }
            if (!rule.isEnabled() || rule.getNextRunTs() > now) {
                continue;
            }
            try {
                automationExecutor.execute(tenantId, rule);
            } finally {
                rule.setNextRunTs(AutomationScheduleCalculator.nextRunTs(rule.getSchedule(), now));
                changed = true;
            }
        }
        if (changed) {
            automationService.saveAutomationRules(tenantId, rules);
        }
    }

    private List<TenantId> listTenantIds() {
        List<TenantId> result = new ArrayList<>();
        PageLink pageLink = new PageLink(TENANT_PAGE_SIZE);
        while (pageLink != null && result.size() < MAX_TENANTS) {
            PageData<TenantId> page = tenantService.findTenantsIds(pageLink);
            result.addAll(page.getData());
            pageLink = page.hasNext() ? pageLink.nextPageLink() : null;
        }
        return result;
    }
}
