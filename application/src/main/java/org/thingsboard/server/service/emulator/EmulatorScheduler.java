// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.emulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.dao.settings.EmulatorService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes the telemetry of the running emulators. Only the tb-core replica that owns the system partition runs
 * the scheduler, so an emulator never publishes twice in a multi replica deployment.
 */
@Slf4j
@TbCoreComponent
@Service
@RequiredArgsConstructor
public class EmulatorScheduler {

    private static final int TENANT_PAGE_SIZE = 500;
    private static final int MAX_TENANTS = 10000;

    private final EmulatorService emulatorService;
    private final EmulatorManager emulatorManager;
    private final TenantService tenantService;
    private final PartitionService partitionService;

    @Scheduled(initialDelayString = "${emulator.scheduler.initial-delay-ms:25000}",
            fixedDelayString = "${emulator.scheduler.interval-ms:5000}")
    public void publishTelemetry() {
        if (!partitionService.isSystemPartitionMine(ServiceType.TB_CORE)) {
            return;
        }
        for (TenantId tenantId : listTenantIds()) {
            List<EmulatorInstance> instances;
            try {
                instances = emulatorService.getEmulators(tenantId);
            } catch (Exception e) {
                log.warn("[{}] Failed to load the emulators", tenantId, e);
                continue;
            }
            for (EmulatorInstance instance : instances) {
                try {
                    emulatorManager.tick(tenantId, instance);
                } catch (Exception e) {
                    log.warn("[{}][{}] Failed to publish emulator telemetry", tenantId, instance.getName(), e);
                }
            }
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
