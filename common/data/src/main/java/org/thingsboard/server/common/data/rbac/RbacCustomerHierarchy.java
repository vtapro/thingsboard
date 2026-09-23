// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Schema
@Data
public class RbacCustomerHierarchy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Customer hierarchy as a map of child customer id to parent customer id")
    private Map<String, String> parents = new HashMap<>();

}

