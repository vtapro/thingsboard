// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Roles of one user: every role of the tenant with the way it applies to the user.
 *
 * <p>Behind the "Roles" section of the user dialog: the tenant administrator ticks the roles of the user directly,
 * on top of the roles the user gets from its user groups (the entity groups of the "Manage owner and groups" dialog).
 * The effective permissions of a user are the union of all of them.
 */
@Schema
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleAssignments {

    private String userId;

    private List<Role> roles = new ArrayList<>();

    @Schema
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Role {

        private String id;

        private String name;

        /** The role is assigned to the user directly (not only through one of its user groups). */
        private boolean direct;

        /** The role applies to the user (directly or through one of its user groups). */
        private boolean assigned;

        /** Names of the user groups that grant the role to the user. */
        private List<String> groups = new ArrayList<>();
    }

}
