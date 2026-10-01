// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.emulator.EmulatorInstance;
import org.thingsboard.server.common.data.emulator.EmulatorProfile;
import org.thingsboard.server.common.data.emulator.EmulatorSignal;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.dashboard.DashboardService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the dashboard of an emulator: one chart per telemetry key of the profile plus an overview chart with every
 * key, all of them bound to the device created for the emulator.
 *
 * <p>The widget configuration is a copy of the time series chart shipped with the platform demo dashboards
 * ({@code emulators/timeseries-chart-widget.json}), so the widgets behaviour and styling do not depend on the
 * version of the UI. The layout, the timewindow and the grid settings come from the same source
 * ({@code emulators/dashboard-settings.json}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmulatorDashboardFactory {

    private static final String WIDGET_TEMPLATE = "emulators/timeseries-chart-widget.json";
    private static final String DASHBOARD_TEMPLATE = "emulators/dashboard-settings.json";

    private static final String[] COLORS = {
            "#EF5350", "#42A5F5", "#66BB6A", "#FFA726", "#AB47BC", "#26C6DA", "#8D6E63", "#78909C"
    };

    private final DashboardService dashboardService;

    public Dashboard createDashboard(TenantId tenantId, EmulatorProfile profile, EmulatorInstance instance) throws Exception {
        JsonNode widgetTemplate = readTemplate(WIDGET_TEMPLATE);
        JsonNode dashboardTemplate = readTemplate(DASHBOARD_TEMPLATE);

        String aliasId = UUID.randomUUID().toString();
        String deviceId = instance.getDeviceId().getId().toString();
        String title = instance.getName() + " — " + profile.getName();

        Map<String, JsonNode> widgets = new LinkedHashMap<>();
        Map<String, ObjectNode> positions = new LinkedHashMap<>();

        // 1. overview chart with every signal of the profile
        String overviewId = UUID.randomUUID().toString();
        widgets.put(overviewId, chart(widgetTemplate, profile.getName(), aliasId, profile.getSignals(), 24, 8));
        positions.put(overviewId, position(0, 0, 24, 8, 1));

        // 2. one chart per signal, two charts per row
        int index = 0;
        for (EmulatorSignal signal : profile.getSignals()) {
            String widgetId = UUID.randomUUID().toString();
            widgets.put(widgetId, chart(widgetTemplate, signal.getLabel(), aliasId, List.of(signal), 12, 6));
            int row = 8 + (index / 2) * 6;
            int col = (index % 2) * 12;
            positions.put(widgetId, position(row, col, 12, 6, index + 2));
            index++;
        }

        ObjectNode state = JacksonUtil.newObjectNode();
        state.put("name", title);
        state.put("root", true);
        ObjectNode layout = JacksonUtil.newObjectNode();
        ObjectNode layoutWidgets = layout.putObject("widgets");
        positions.forEach(layoutWidgets::set);
        layout.set("gridSettings", dashboardTemplate.get("gridSettings").deepCopy());
        ObjectNode layouts = state.putObject("layouts");
        layouts.set("main", layout);

        ObjectNode states = JacksonUtil.newObjectNode();
        states.set("default", state);

        ObjectNode alias = JacksonUtil.newObjectNode();
        alias.put("id", aliasId);
        alias.put("alias", "Emulator device");
        ObjectNode filter = alias.putObject("filter");
        filter.put("type", "entityList");
        filter.put("resolveMultiple", true);
        filter.put("entityType", "DEVICE");
        ArrayNode entityList = filter.putArray("entityList");
        entityList.add(deviceId);
        ObjectNode aliases = JacksonUtil.newObjectNode();
        aliases.set(aliasId, alias);

        ObjectNode configuration = JacksonUtil.newObjectNode();
        ObjectNode widgetNodes = configuration.putObject("widgets");
        widgets.forEach(widgetNodes::set);
        configuration.set("states", states);
        configuration.set("entityAliases", aliases);
        configuration.set("timewindow", dashboardTemplate.get("timewindow").deepCopy());
        configuration.set("settings", dashboardTemplate.get("settings").deepCopy());
        configuration.putArray("filters");

        Dashboard dashboard = new Dashboard();
        dashboard.setTenantId(tenantId);
        dashboard.setTitle(title);
        dashboard.setConfiguration(configuration);
        Dashboard saved = dashboardService.saveDashboard(dashboard);
        log.info("[{}][{}] Created emulator dashboard [{}][{}]", tenantId, instance.getName(), saved.getId(), title);
        return saved;
    }

    /**
     * One chart widget: the time series chart template with the alias of the emulator device and the keys to plot.
     */
    private JsonNode chart(JsonNode template, String title, String aliasId,
                           List<EmulatorSignal> signals, int sizeX, int sizeY) {
        ObjectNode widget = (ObjectNode) template.deepCopy();
        widget.put("sizeX", sizeX);
        widget.put("sizeY", sizeY);
        ObjectNode config = (ObjectNode) widget.get("config");
        config.put("title", title);
        ObjectNode datasource = (ObjectNode) config.get("datasources").get(0);
        datasource.put("entityAliasId", aliasId);
        JsonNode keyTemplate = datasource.get("dataKeys").get(0);
        ArrayNode dataKeys = datasource.putArray("dataKeys");
        int index = 0;
        for (EmulatorSignal signal : signals) {
            ObjectNode key = (ObjectNode) keyTemplate.deepCopy();
            key.put("name", signal.getKey());
            key.put("label", signal.getLabel());
            key.put("units", signal.getUnit() == null ? "" : signal.getUnit());
            key.put("decimals", signal.getDecimals());
            key.put("color", COLORS[index % COLORS.length]);
            key.remove("_hash");
            dataKeys.add(key);
            index++;
        }
        return widget;
    }

    private ObjectNode position(int row, int col, int sizeX, int sizeY, int mobileOrder) {
        ObjectNode position = JacksonUtil.newObjectNode();
        position.put("sizeX", sizeX);
        position.put("sizeY", sizeY);
        position.put("row", row);
        position.put("col", col);
        position.put("mobileOrder", mobileOrder);
        position.put("mobileHeight", 5);
        return position;
    }

    private JsonNode readTemplate(String path) throws Exception {
        try (var inputStream = new ClassPathResource(path).getInputStream()) {
            return JacksonUtil.OBJECT_MAPPER.readTree(inputStream);
        }
    }

}
