// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.rbac;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.EntityId;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity group membership of one entity: which groups of its entity type the entity belongs to.
 *
 * <p>This is the payload behind the "Manage owner and groups" dialog: the owner of the entity is changed with the
 * standard API of the entity type (e.g. {@code POST /api/user}), the groups with
 * {@code POST /api/tenant/entityGroup/members/{entityType}/{entityId}}.
 */
@Schema
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityGroupMembers {

    private EntityId entityId;

    private List<Member> groups = new ArrayList<>();

    @Schema
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Member {

        private String id;

        private String name;

        private String description;

        private boolean publicGroup;

        /**
         * The "All" group of the entity type: every entity of the tenant belongs to it and it can not be changed.
         */
        private boolean allGroup;

        /**
         * A group whose membership is derived from the platform itself and therefore can not be changed by the
         * "Manage owner and groups" dialog: the "All" group of the entity type and the default user groups that
         * follow the authority of the user ("Tenant Administrators" / "Tenant Users").
         */
        private boolean system;

        private boolean member;
    }

}
