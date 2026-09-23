// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Schema
@Data
public class RbacRoleSettings implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Roles of the tenant")
    private List<RbacRole> roles = new ArrayList<>();

}

