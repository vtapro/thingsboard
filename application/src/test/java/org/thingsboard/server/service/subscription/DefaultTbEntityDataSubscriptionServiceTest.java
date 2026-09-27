// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.subscription;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.query.TsValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultTbEntityDataSubscriptionServiceTest {

    /**
     * Hybrid mode (the latest values are kept in Cassandra): the SQL entity data query left joins the
     * 'ts_kv_latest' table, so every requested key without a row in that table is present in the result
     * with a placeholder value (ts = 0, empty value). Such keys must be reported as missing, otherwise
     * the widgets receive the placeholders and display "N/A".
     */
    @Test
    public void givenSqlLatestValuesWithPlaceholders_whenResolvingMissingTsKeys_thenPlaceholdersAreMissing() {
        Map<String, TsValue> sqlLatestValues = new HashMap<>();
        sqlLatestValues.put("temperature", TsValue.EMPTY);
        sqlLatestValues.put("humidity", new TsValue(0, ""));
        sqlLatestValues.put("battery", new TsValue(1790512030598L, "86"));

        assertThat(DefaultTbEntityDataSubscriptionService.getMissingTsKeys(
                List.of("temperature", "humidity", "battery", "pressure"), sqlLatestValues))
                .containsExactlyInAnyOrder("temperature", "humidity", "pressure");
    }

    @Test
    public void givenNoSqlLatestValues_whenResolvingMissingTsKeys_thenAllKeysAreMissing() {
        assertThat(DefaultTbEntityDataSubscriptionService.getMissingTsKeys(List.of("temperature"), null))
                .containsExactly("temperature");
    }

    @Test
    public void givenSqlLatestValues_whenResolvingMissingTsKeys_thenOnlyAbsentKeysAreMissing() {
        Map<String, TsValue> sqlLatestValues = new HashMap<>();
        sqlLatestValues.put("temperature", new TsValue(1790512030598L, "25"));
        sqlLatestValues.put("humidity", new TsValue(1790512030599L, "52"));

        assertThat(DefaultTbEntityDataSubscriptionService.getMissingTsKeys(
                List.of("temperature", "humidity", "battery"), sqlLatestValues))
                .containsExactly("battery");
    }

}
