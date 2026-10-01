// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.emulator.EmulatorCatalogData;
import org.thingsboard.server.common.data.emulator.EmulatorCreateRequest;
import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.emulator.EmulatorProfile;
import org.thingsboard.server.common.data.emulator.EmulatorStatus;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.emulator.EmulatorCatalog;
import org.thingsboard.server.service.emulator.EmulatorManager;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

/**
 * Emulators of the current tenant: the catalog of virtual device profiles (energy, agriculture, industrial,
 * lighting, water, transportation, buildings, smart cities) and the emulators created out of it.
 *
 * <p>Creating an emulator creates a device, starts publishing telemetry for the domain of the profile and (by
 * default) creates the dashboard of that domain, so a tenant gets a working demo of a use case in one call.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@TbCoreComponent
@RequestMapping("/api/tenant/emulator")
public class EmulatorController extends BaseController {

    private final EmulatorCatalog emulatorCatalog;
    private final EmulatorManager emulatorManager;

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Get the emulator catalog (getEmulatorCatalog)",
            notes = "Returns the virtual device profiles a tenant may emulate together with their categories. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/catalog")
    public EmulatorCatalogData getEmulatorCatalog() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        List<EmulatorProfile> profiles = emulatorCatalog.getProfiles();
        List<String> types = profiles.stream().map(EmulatorProfile::getType).distinct().sorted().toList();
        return new EmulatorCatalogData(emulatorCatalog.getCategories(), types, profiles);
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Get the emulators (getEmulators)",
            notes = "Returns the emulators of the current tenant with their status, scenario and dashboard. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping
    public List<EmulatorInstance> getEmulators() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.READ);
        return emulatorManager.list(getTenantId());
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Create an emulator (createEmulator)",
            notes = "Creates a device, its dashboard and starts emulating the profile. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping
    public EmulatorInstance createEmulator(@RequestBody EmulatorCreateRequest request) throws Exception {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return emulatorManager.create(getCurrentUser(), request.getProfileId(), request.getName(),
                request.getScenario(), request.getIntervalSeconds(), request.isCreateDashboard());
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Start, stop or pause an emulator (setEmulatorStatus)",
            notes = "Only a running emulator publishes telemetry. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{instanceId}/{status}")
    public EmulatorInstance setEmulatorStatus(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("status") String status) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        EmulatorStatus emulatorStatus;
        try {
            emulatorStatus = EmulatorStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Emulator status must be one of RUNNING, PAUSED, STOPPED");
        }
        return emulatorManager.setStatus(getTenantId(), instanceId, emulatorStatus);
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Change the scenario or the interval of an emulator (updateEmulator)",
            notes = "The scenario changes how the emulated values move (e.g. \"Grid Blackout\" drops the solar "
                    + "production), the interval how often telemetry is published. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{instanceId}/scenario")
    public EmulatorInstance updateEmulator(
            @PathVariable("instanceId") String instanceId,
            @Parameter(description = "Scenario name of the profile")
            @RequestParam(required = false) String scenario,
            @Parameter(description = "Publish interval in seconds")
            @RequestParam(required = false) Integer intervalSeconds) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return emulatorManager.update(getTenantId(), instanceId, scenario, intervalSeconds);
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Create (or recreate) the dashboard of an emulator (createEmulatorDashboard)",
            notes = "Creates the dashboard of the domain of the emulator with one chart per telemetry key, bound to "
                    + "the device of the emulator. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{instanceId}/dashboard")
    public EmulatorInstance createEmulatorDashboard(@PathVariable("instanceId") String instanceId) throws Exception {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        return emulatorManager.createDashboard(getTenantId(), instanceId);
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Generate the history of an emulator (generateEmulatorHistory)",
            notes = "Publishes synthetic telemetry of the last N hours, so the dashboard of the emulator shows data "
                    + "immediately. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/{instanceId}/history")
    public Map<String, Object> generateEmulatorHistory(
            @PathVariable("instanceId") String instanceId,
            @Parameter(description = "Number of hours to generate (default 24)")
            @RequestParam(required = false, defaultValue = "24") int hours) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        int published = emulatorManager.generateHistory(getTenantId(), instanceId, hours);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", published);
        return result;
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Delete an emulator (deleteEmulator)",
            notes = "Deletes the emulator (and, by default, the device created for it). " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping("/{instanceId}")
    public void deleteEmulator(
            @PathVariable("instanceId") String instanceId,
            @Parameter(description = "Also delete the device created for the emulator (default true)")
            @RequestParam(required = false, defaultValue = "true") boolean deleteDevice) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        emulatorManager.delete(getTenantId(), instanceId, deleteDevice);
    }

    @org.thingsboard.server.config.annotations.ApiOperation(
            value = "Remove the emulators whose device was deleted (clearUnlinkedEmulators)",
            notes = "Deletes the emulator records that do not have a device anymore, like the \"Clear Unlinked\" "
                    + "action of ThingsBoard PE. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/clearUnlinked")
    public Map<String, Object> clearUnlinkedEmulators() throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.ADMIN_SETTINGS, Operation.WRITE);
        int cleared = emulatorManager.clearUnlinked(getTenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", cleared);
        return result;
    }

}
