// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.menu;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Schema
@Data
public class CustomMenuSettings implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Ordered list of custom menu items")
    private List<CustomMenuItem> items = new ArrayList<>();

}

