// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit grant of a set of operations on one entity to a user or to a user group. Shares are configured by the
 * tenant administrator only and are checked before the custom role of the user (see TbRbacAccessControlService).
 */
@Schema
@Data
public class RbacShare implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Share id")
    private String id;

    @Schema(description = "Entity type, e.g. DEVICE")
    private String entityType;

    @Schema(description = "Id of the shared entity")
    private String entityId;

    @Schema(description = "USER or USER_GROUP")
    private String assigneeType;

    @Schema(description = "Id of the user or of the user group")
    private String assigneeId;

    @Schema(description = "Operations granted by the share")
    private List<String> operations = new ArrayList<>();

}
