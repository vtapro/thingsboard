// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Request of "set the groups of one entity": the entity belongs to exactly the groups listed here after the call.
 */
@Schema
@Data
public class EntityGroupMembersRequest {

    private List<String> groupIds;
}
