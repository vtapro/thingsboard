// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.emulator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload of the emulator catalog: the profiles a tenant may create and the categories they belong to.
 */
@Schema
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmulatorCatalogData {

    private List<String> categories;

    private List<String> types;

    private List<EmulatorProfile> profiles;
}
