// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Set;
import java.util.UUID;

public interface AccessControlService {

    void checkPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException;

    boolean hasPermission(SecurityUser user, Resource resource, Operation operation) throws ThingsboardException;

    <I extends EntityId, T extends HasTenantId> void checkPermission(SecurityUser user, Resource resource, Operation operation, I entityId, T entity) throws ThingsboardException;

    <I extends EntityId, T extends HasTenantId> boolean hasPermission(SecurityUser user, Resource resource, Operation operation, I entityId, T entity) throws ThingsboardException;

    /**
     * When the operation of the user is granted by a custom role only for the entities of some entity groups, this
     * method returns the ids of the entities the user may access. {@code null} means that the user is not limited by
     * the entity groups (no custom role, or the operation is granted globally).
     * <p>The list endpoints use it to filter the results, the entity aware checks use it to allow an entity that
     * belongs to one of the groups.
     */
    default Set<UUID> getAllowedEntityIds(SecurityUser user, Resource resource, Operation operation) {
        return null;
    }

}
