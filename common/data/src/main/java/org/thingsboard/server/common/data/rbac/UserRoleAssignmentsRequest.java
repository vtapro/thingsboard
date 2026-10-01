// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Request of "set the roles assigned directly to one user": after the call the user is assigned to exactly the
 * listed roles (the assignments of the other users are not touched, and the roles the user gets from its user groups
 * are not affected).
 */
@Schema
@Data
public class UserRoleAssignmentsRequest {

    private List<String> roleIds;
}
