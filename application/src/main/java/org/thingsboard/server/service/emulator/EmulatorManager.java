// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.emulator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.FutureCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.emulator.EmulatorProfile;
import org.thingsboard.server.common.data.emulator.EmulatorStatus;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.EmulatorService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;
import org.thingsboard.server.queue.provider.TbQueueProducerProvider;
import org.thingsboard.server.queue.TbQueueCallback;
import org.thingsboard.server.queue.TbQueueMsgMetadata;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.state.DefaultDeviceStateService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.common.data.exception.ThingsboardErrorCode.BAD_REQUEST_PARAMS;
import static org.thingsboard.server.common.data.exception.ThingsboardErrorCode.ITEM_NOT_FOUND;

/**
 * Creates, runs and removes the emulators of a tenant.
 *
 * <p>An emulator is a real device of the tenant (it has an access token and appears in the Devices page) plus a
 * generator that publishes telemetry to the rule engine every {@code intervalSeconds}, so the emulated values also
 * feed the rule chains, alarms and dashboards of the tenant. The runtime state (last published value, counters) is
 * kept in memory and persisted to AdminSettings at most once per minute.
 */
@Slf4j
@TbCoreComponent
@Service
@RequiredArgsConstructor
public class EmulatorManager {

    private static final long PERSIST_INTERVAL_MS = 60_000;
    private static final int MAX_HISTORY_MESSAGES = 500;
    private static final TbQueueCallback EMPTY_CALLBACK = new TbQueueCallback() {
        @Override
        public void onSuccess(TbQueueMsgMetadata metadata) {
        }

        @Override
        public void onFailure(Throwable t) {
        }
    };

    private final EmulatorService emulatorService;
    private final EmulatorCatalog catalog;
    private final EmulatorGenerator generator;
    private final EmulatorDashboardFactory dashboardFactory;
    private final DeviceService deviceService;
    private final DeviceProfileService deviceProfileService;
    private final TbClusterService tbClusterService;
    private final PartitionService partitionService;
    private final TbQueueProducerProvider tbQueueProducerProvider;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final TelemetrySubscriptionService tsSubService;

    private final Map<String, Long> lastPublishTs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPersistTs = new ConcurrentHashMap<>();
    private final Map<String, Device> deviceCache = new ConcurrentHashMap<>();
    private final Set<String> inactivityConfigured = ConcurrentHashMap.newKeySet();

    public List<EmulatorInstance> list(TenantId tenantId) {
        List<EmulatorInstance> instances = emulatorService.getEmulators(tenantId);
        instances.sort(Comparator.comparingLong(EmulatorInstance::getCreatedTime).reversed());
        return instances;
    }

    public EmulatorInstance create(SecurityUser user, String profileId, String name, String scenario,
                                   Integer intervalSeconds, boolean createDashboard) throws Exception {
        TenantId tenantId = user.getTenantId();
        EmulatorProfile profile = catalog.findProfile(profileId)
                .orElseThrow(() -> new ThingsboardException("Unknown emulator profile " + profileId, BAD_REQUEST_PARAMS));

        List<EmulatorInstance> existing = emulatorService.getEmulators(tenantId);
        String emulatorName = name != null && !name.isBlank()
                ? name.trim()
                : profileId + "-" + String.format("%03d", countOf(existing, profileId) + 1);
        emulatorName = uniqueName(tenantId, emulatorName);

        Device device = new Device();
        device.setTenantId(tenantId);
        device.setName(emulatorName);
        device.setLabel(profile.getName());
        device.setType(profile.getId());
        device.setDeviceProfileId(deviceProfileService
                .findOrCreateDeviceProfile(tenantId, profile.getDeviceProfile()).getId());
        Device savedDevice = deviceService.saveDeviceWithAccessToken(device, accessToken());

        EmulatorInstance instance = new EmulatorInstance();
        instance.setId(UUID.randomUUID().toString());
        instance.setProfileId(profile.getId());
        instance.setProfileName(profile.getName());
        instance.setCategory(profile.getCategory());
        instance.setName(emulatorName);
        instance.setDeviceId(savedDevice.getId());
        instance.setDeviceName(savedDevice.getName());
        instance.setStatus(EmulatorStatus.STOPPED);
        instance.setScenario(scenario != null && !scenario.isBlank() ? scenario : profile.getDefaultScenario());
        instance.setIntervalSeconds(intervalSeconds != null && intervalSeconds > 0
                ? intervalSeconds : profile.getIntervalSeconds());
        instance.setCreatedTime(System.currentTimeMillis());
        // the device state service (active/inactive) needs to know how long the emulator may stay silent
        updateInactivityTimeout(savedDevice, instance.getIntervalSeconds());
        emulatorService.saveEmulator(tenantId, instance);

        if (createDashboard) {
            createDashboard(tenantId, instance);
        }
        return instance;
    }

    public EmulatorInstance setStatus(TenantId tenantId, String instanceId, EmulatorStatus status) throws ThingsboardException {
        EmulatorInstance instance = get(tenantId, instanceId);
        instance.setStatus(status);
        if (status == EmulatorStatus.RUNNING) {
            // publish the first value right away
            lastPublishTs.remove(key(tenantId, instanceId));
            if (instance.getScenario() == null || instance.getScenario().isBlank()) {
                catalog.findProfile(instance.getProfileId())
                        .ifPresent(profile -> instance.setScenario(profile.getDefaultScenario()));
            }
            markDeviceActive(tenantId, instance);
        }
        return emulatorService.saveEmulator(tenantId, instance);
    }

    public EmulatorInstance update(TenantId tenantId, String instanceId, String scenario,
                                   Integer intervalSeconds) throws ThingsboardException {
        EmulatorInstance instance = get(tenantId, instanceId);
        if (scenario != null && !scenario.isBlank()) {
            instance.setScenario(scenario);
        }
        if (intervalSeconds != null && intervalSeconds > 0) {
            instance.setIntervalSeconds(intervalSeconds);
            Device device = findDevice(tenantId, instance);
            if (device != null) {
                updateInactivityTimeout(device, intervalSeconds);
            }
        }
        lastPublishTs.remove(key(tenantId, instanceId));
        return emulatorService.saveEmulator(tenantId, instance);
    }

    public void delete(TenantId tenantId, String instanceId, boolean deleteDevice) {
        EmulatorInstance instance = emulatorService.getEmulators(tenantId).stream()
                .filter(item -> item.getId().equals(instanceId)).findFirst().orElse(null);
        if (instance == null) {
            return;
        }
        emulatorService.deleteEmulator(tenantId, instanceId);
        lastPublishTs.remove(key(tenantId, instanceId));
        lastPersistTs.remove(key(tenantId, instanceId));
        deviceCache.remove(instanceId);
        inactivityConfigured.remove(instanceId);
        if (deleteDevice && instance.getDeviceId() != null) {
            try {
                deviceService.deleteDevice(tenantId, instance.getDeviceId());
            } catch (Exception e) {
                log.warn("[{}] Failed to delete the device {} of emulator {}",
                        tenantId, instance.getDeviceId(), instance.getName(), e);
            }
        }
    }

    public EmulatorInstance createDashboard(TenantId tenantId, EmulatorInstance instance) throws Exception {
        EmulatorProfile profile = catalog.findProfile(instance.getProfileId())
                .orElseThrow(() -> new ThingsboardException("Unknown emulator profile " + instance.getProfileId(),
                        BAD_REQUEST_PARAMS));
        Dashboard dashboard = dashboardFactory.createDashboard(tenantId, profile, instance);
        instance.setDashboardId(dashboard.getId().getId());
        instance.setDashboardTitle(dashboard.getTitle());
        return emulatorService.saveEmulator(tenantId, instance);
    }

    public EmulatorInstance createDashboard(TenantId tenantId, String instanceId) throws Exception {
        return createDashboard(tenantId, get(tenantId, instanceId));
    }

    /**
     * Removes the emulators whose device no longer exists (the device was deleted outside of the emulator page).
     *
     * @return number of emulator records that were removed
     */
    public int clearUnlinked(TenantId tenantId) {
        int cleared = 0;
        for (EmulatorInstance instance : emulatorService.getEmulators(tenantId)) {
            if (instance.getDeviceId() == null
                    || deviceService.findDeviceById(tenantId, instance.getDeviceId()) == null) {
                delete(tenantId, instance.getId(), false);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Publishes synthetic history of the emulator, so a dashboard created for it is not empty.
     *
     * @return number of telemetry messages published
     */
    public int generateHistory(TenantId tenantId, String instanceId, int hours) throws ThingsboardException {
        EmulatorInstance instance = get(tenantId, instanceId);
        EmulatorProfile profile = catalog.findProfile(instance.getProfileId())
                .orElseThrow(() -> new ThingsboardException("Unknown emulator profile " + instance.getProfileId(),
                        BAD_REQUEST_PARAMS));
        int windowHours = hours > 0 ? hours : 24;
        long now = System.currentTimeMillis();
        long windowMs = windowHours * 3600_000L;
        long stepMs = Math.max(instance.getIntervalSeconds(), 1) * 1000L;
        stepMs = Math.max(stepMs, windowMs / MAX_HISTORY_MESSAGES);
        long tick = 0;
        int published = 0;
        for (long ts = now - windowMs; ts <= now; ts += stepMs) {
            ObjectNode values = generator.generate(profile, instance.getScenario(), tick++, ts);
            publish(tenantId, instance.getDeviceId(), values, ts);
            published++;
        }
        // the live values continue where the history ended, so a counter never drops back to zero
        instance.setPublishedMessages(Math.max(instance.getPublishedMessages(), tick));
        instance.setLastActivityTs(now);
        emulatorService.saveEmulator(tenantId, instance);
        log.info("[{}][{}] Generated {} history points", tenantId, instance.getName(), published);
        return published;
    }

    /**
     * One cycle of the emulator: publishes telemetry when the configured interval elapsed.
     */
    public void tick(TenantId tenantId, EmulatorInstance instance) {
        if (instance.getStatus() != EmulatorStatus.RUNNING || instance.getDeviceId() == null) {
            return;
        }
        EmulatorProfile profile = catalog.findProfile(instance.getProfileId()).orElse(null);
        if (profile == null) {
            log.warn("[{}][{}] Emulator profile {} no longer exists, stopping the emulator",
                    tenantId, instance.getName(), instance.getProfileId());
            instance.setStatus(EmulatorStatus.STOPPED);
            emulatorService.saveEmulator(tenantId, instance);
            return;
        }
        String key = key(tenantId, instance.getId());
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(instance.getIntervalSeconds(), 1) * 1000L;
        Long last = lastPublishTs.get(key);
        if (last != null && now - last < intervalMs) {
            return;
        }
        lastPublishTs.put(key, now);
        if (inactivityConfigured.add(instance.getId())) {
            // emulators created before this release, or before a restart of the service, get the inactivity
            // timeout of their interval once, so the device state service does not see them flapping
            Device device = findDevice(tenantId, instance);
            if (device != null) {
                updateInactivityTimeout(device, instance.getIntervalSeconds());
            }
        }
        long tick = instance.getPublishedMessages();
        ObjectNode values = generator.generate(profile, instance.getScenario(), tick, now);
        publish(tenantId, instance.getDeviceId(), values, now);
        // the rule engine stores the telemetry, the device actor keeps the device "active"
        markDeviceActive(tenantId, instance);
        instance.setPublishedMessages(tick + 1);
        instance.setLastActivityTs(now);
        Long lastPersist = lastPersistTs.get(key);
        if (lastPersist == null || now - lastPersist >= PERSIST_INTERVAL_MS) {
            lastPersistTs.put(key, now);
            emulatorService.saveEmulator(tenantId, instance);
        }
    }

    public EmulatorInstance get(TenantId tenantId, String instanceId) throws ThingsboardException {
        return emulatorService.getEmulators(tenantId).stream()
                .filter(instance -> instance.getId().equals(instanceId)).findFirst()
                .orElseThrow(() -> new ThingsboardException("Emulator not found: " + instanceId, ITEM_NOT_FOUND));
    }

    private void publish(TenantId tenantId, DeviceId deviceId, ObjectNode values, long ts) {
        TbMsgMetaData metadata = new TbMsgMetaData();
        metadata.putValue("ts", Long.toString(ts));
        metadata.putValue("emulator", "true");
        TbMsg msg = TbMsg.newMsg()
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .copyMetaData(metadata)
                .dataType(TbMsgDataType.JSON)
                .data(JacksonUtil.toString(values))
                .build();
        tbClusterService.pushMsgToRuleEngine(tenantId, deviceId, msg, EMPTY_CALLBACK);
    }

    /**
     * Tells the device state service that the device of the emulator is alive, exactly like a device that
     * publishes telemetry over MQTT does: the inactive timeout is stored as a server attribute and every
     * published value sends the activity timestamp to the device actor.
     */
    private void markDeviceActive(TenantId tenantId, EmulatorInstance instance) {
        Device device = findDevice(tenantId, instance);
        if (device == null) {
            log.debug("[{}][{}] Device {} is gone, emulator stays silent",
                    tenantId, instance.getName(), instance.getDeviceId());
            return;
        }
        TopicPartitionInfo tpi = partitionService.resolve(ServiceType.TB_CORE, tenantId, device.getId());
        UUID sessionId = UUID.randomUUID();
        DeviceProfileId profileId = device.getDeviceProfileId();
        TransportProtos.TransportToDeviceActorMsg msg = TransportProtos.TransportToDeviceActorMsg.newBuilder()
                .setSessionInfo(TransportProtos.SessionInfoProto.newBuilder()
                        .setSessionIdMSB(sessionId.getMostSignificantBits())
                        .setSessionIdLSB(sessionId.getLeastSignificantBits())
                        .setDeviceIdMSB(device.getId().getId().getMostSignificantBits())
                        .setDeviceIdLSB(device.getId().getId().getLeastSignificantBits())
                        .setDeviceProfileIdMSB(profileId == null ? 0 : profileId.getId().getMostSignificantBits())
                        .setDeviceProfileIdLSB(profileId == null ? 0 : profileId.getId().getLeastSignificantBits())
                        .setDeviceName(device.getName())
                        .setDeviceType(device.getType() == null ? "" : device.getType())
                        .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                        .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                        .setNodeId(serviceInfoProvider.getServiceId())
                        .build())
                .setSubscriptionInfo(TransportProtos.SubscriptionInfoProto.newBuilder()
                        .setLastActivityTime(System.currentTimeMillis())
                        .build())
                .build();
        tbQueueProducerProvider.getTbCoreMsgProducer().send(tpi,
                new TbProtoQueueMsg<>(device.getId().getId(),
                        TransportProtos.ToCoreMsg.newBuilder().setToDeviceActorMsg(msg).build()),
                EMPTY_CALLBACK);
    }

    /**
     * An emulator may stay silent for one interval (and a little more): the inactivity timeout of the device
     * state service is set accordingly, so a running emulator does not flap between active and inactive.
     */
    private void updateInactivityTimeout(Device device, int intervalSeconds) {
        long inactivityTimeout = Math.max(TimeUnit.SECONDS.toMillis(Math.max(intervalSeconds, 1)) * 3, 60_000);
        tsSubService.saveAttributes(AttributesSaveRequest.builder()
                .tenantId(device.getTenantId())
                .entityId(device.getId())
                .scope(AttributeScope.SERVER_SCOPE)
                .entry(new LongDataEntry(DefaultDeviceStateService.INACTIVITY_TIMEOUT, inactivityTimeout))
                .callback(new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable Void unused) {
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        log.warn("[{}] Failed to set the inactivity timeout of the emulator device {}",
                                device.getTenantId(), device.getName(), throwable);
                    }
                })
                .build());
    }

    private Device findDevice(TenantId tenantId, EmulatorInstance instance) {
        Device cached = deviceCache.get(instance.getId());
        if (cached != null) {
            return cached;
        }
        Device device = deviceService.findDeviceById(tenantId, instance.getDeviceId());
        if (device != null) {
            deviceCache.put(instance.getId(), device);
        }
        return device;
    }

    private String uniqueName(TenantId tenantId, String name) {
        String candidate = name;
        int suffix = 2;
        while (deviceService.findDeviceByTenantIdAndName(tenantId, candidate) != null) {
            candidate = name + "-" + suffix++;
        }
        return candidate;
    }

    private static int countOf(List<EmulatorInstance> instances, String profileId) {
        return (int) instances.stream().filter(instance -> profileId.equals(instance.getProfileId())).count();
    }

    private static String key(TenantId tenantId, String instanceId) {
        return tenantId.getId() + ":" + instanceId;
    }

    private static String accessToken() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

}
