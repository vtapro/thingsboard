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

    /**
     * True when a custom role applies to the user, i.e. the permissions of the user may be narrower than the platform
     * permissions. It is used by the entity data queries of the WEB UI, that are executed without the entity aware
     * permission checks, to decide whether the results have to be filtered.
     */
    default boolean hasCustomRole(SecurityUser user) {
        return false;
    }

    /**
     * Ids of the customers the user may see because of its customer scope (the customer of the user and, when the
     * custom role enables the customer hierarchy, its sub-customers). {@code null} means that the platform behaviour
     * applies (the user only sees its own customer).
     * <p>Used by the customer and user list endpoints, that have to filter the results in memory for the roles that
     * are scoped to the customer hierarchy.
     */
    default Set<UUID> getAccessibleCustomerIds(SecurityUser user) {
        return null;
    }

    /**
     * Called after an entity that may be scoped to its creator was created. An implementation that caches the owner
     * of the entities (see the "only the entities created by the user" flag of a role) drops the stale cache here,
     * otherwise the list pages would not show the entity the user has just created.
     */
    default void onEntityCreated(SecurityUser user) {
    }

    /**
     * Called after the permission settings of a tenant changed (roles, entity groups, user groups, shares), so an
     * implementation that caches them drops the stale entries and the change takes effect immediately instead of
     * after the cache expires.
     */
    default void onPermissionsChanged() {
    }

}
