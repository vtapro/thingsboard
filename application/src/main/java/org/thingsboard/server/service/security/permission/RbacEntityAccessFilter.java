// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.dao.entity.EntityService;
import org.thingsboard.server.dao.sql.query.EntityKeyMapping;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Helper for the entity queries that are executed without the entity aware permission checks, i.e. the entity data
 * queries of the WEB UI (the REST controller and the WebSocket subscriptions).
 *
 * <p>The queries are executed by the DAO with the tenant (and the customer) of the user only, so the custom roles have
 * to filter the results here. The filter is based on {@link AccessControlService#getAllowedEntityIds(SecurityUser,
 * Resource, Operation)}: when it returns {@code null} the role of the user does not restrict the resource and the
 * results are returned untouched, otherwise only the entities of the returned set are kept.
 *
 * <p>The filtering is done in memory, therefore the count of the entities is based on the first {@link #SCAN_SIZE}
 * entities of the query.
 */
public final class RbacEntityAccessFilter {

    /**
     * The maximum number of entities that are scanned to count the entities the user is allowed to read.
     */
    public static final int SCAN_SIZE = 1000;

    private RbacEntityAccessFilter() {
    }

    /**
     * The ids of the entities of the given type the user is allowed to read, or {@code null} when the role of the user
     * does not restrict this type of entities.
     */
    public static Set<UUID> allowedEntityIds(AccessControlService accessControlService, SecurityUser user,
                                             EntityType entityType, Operation operation) {
        Resource resource;
        try {
            resource = Resource.of(entityType);
        } catch (IllegalArgumentException e) {
            // the entity type is not a resource of the platform, so no custom role may restrict it
            return null;
        }
        return accessControlService.getAllowedEntityIds(user, resource, operation);
    }

    /**
     * Counts the entities of the query the user is allowed to read. The DAO count can not be scoped by the custom
     * roles, so the count is based on the first {@link #SCAN_SIZE} entities of the query: it is exact when the result
     * of the query is not larger, otherwise it is the number of the allowed entities of the scanned part.
     */
    public static long countAllowedEntities(AccessControlService accessControlService, EntityService entityService,
                                            SecurityUser user, TenantId tenantId, CustomerId customerId,
                                            EntityCountQuery query) {
        long totalCount = entityService.countEntitiesByQuery(tenantId, customerId, query);
        if (user == null || !accessControlService.hasCustomRole(user)) {
            return totalCount;
        }
        PageData<EntityData> scanned = entityService.findEntityDataByQuery(tenantId, customerId, scanQuery(query));
        List<EntityData> allowed = filterEntityData(accessControlService, user, scanned.getData());
        if (!scanned.hasNext()) {
            // the whole result has been scanned, so the count is exact
            return allowed.size();
        }
        if (allowed.size() == scanned.getData().size()) {
            // the custom role did not filter the scanned entities, so the total count of the DAO is used
            return totalCount;
        }
        return allowed.size();
    }

    /**
     * Builds the query that scans the first {@link #SCAN_SIZE} entities of the given count query.
     */
    public static EntityDataQuery scanQuery(EntityCountQuery query) {
        return new EntityDataQuery(query.getEntityFilter(),
                new EntityDataPageLink(SCAN_SIZE, 0, null,
                        new EntityDataSortOrder(new EntityKey(EntityKeyType.ENTITY_FIELD, EntityKeyMapping.CREATED_TIME))),
                null, null, query.getKeyFilters(), query.getKeyFiltersOperationOrDefault());
    }

    public static List<EntityData> filterEntityData(AccessControlService accessControlService, SecurityUser user,
                                                    List<EntityData> data) {
        if (user == null || data == null || data.isEmpty()) {
            return data;
        }
        AllowedIdsCache cache = new AllowedIdsCache(accessControlService, user);
        List<EntityData> result = new ArrayList<>(data.size());
        for (EntityData entityData : data) {
            EntityId entityId = entityData.getEntityId();
            if (entityId != null && cache.isAllowed(entityId)) {
                result.add(entityData);
            }
        }
        return result;
    }

    /**
     * Removes the entities the user is not allowed to read from the page. The number of the total elements is
     * recalculated only when the whole result of the query is in the page, otherwise the number reported by the DAO is
     * kept, because the number of the entities the user is allowed to read is unknown.
     */
    public static PageData<EntityData> filterEntityData(AccessControlService accessControlService, SecurityUser user,
                                                        PageData<EntityData> page, int pageSize) {
        if (user == null || page == null || !accessControlService.hasCustomRole(user)) {
            return page;
        }
        List<EntityData> filtered = filterEntityData(accessControlService, user, page.getData());
        if (filtered.size() == page.getData().size()) {
            return page;
        }
        if (page.hasNext()) {
            return new PageData<>(filtered, page.getTotalPages(), page.getTotalElements(), true);
        }
        int size = Math.max(pageSize, 1);
        return new PageData<>(filtered, (int) Math.ceil((double) filtered.size() / size), filtered.size(), false);
    }

    public static List<EntityId> filterEntityIds(AccessControlService accessControlService, SecurityUser user,
                                                 List<EntityId> entityIds) {
        if (user == null || entityIds == null || entityIds.isEmpty()) {
            return entityIds;
        }
        AllowedIdsCache cache = new AllowedIdsCache(accessControlService, user);
        List<EntityId> result = new ArrayList<>(entityIds.size());
        for (EntityId entityId : entityIds) {
            if (cache.isAllowed(entityId)) {
                result.add(entityId);
            }
        }
        return result;
    }

    /**
     * Resolves the allowed entity ids of an entity type at most once, so that a result that mixes several entity types
     * does not query the RBAC settings for every entity.
     */
    private static final class AllowedIdsCache {

        private final AccessControlService accessControlService;
        private final SecurityUser user;
        private final Map<EntityType, Set<UUID>> allowedIdsByType = new HashMap<>();
        private final Set<EntityType> unrestrictedTypes = new HashSet<>();

        private AllowedIdsCache(AccessControlService accessControlService, SecurityUser user) {
            this.accessControlService = accessControlService;
            this.user = user;
        }

        private boolean isAllowed(EntityId entityId) {
            EntityType entityType = entityId.getEntityType();
            if (unrestrictedTypes.contains(entityType)) {
                return true;
            }
            Set<UUID> allowedIds = allowedIdsByType.get(entityType);
            if (allowedIds == null) {
                allowedIds = allowedEntityIds(accessControlService, user, entityType, Operation.READ);
                if (allowedIds == null) {
                    unrestrictedTypes.add(entityType);
                    return true;
                }
                allowedIdsByType.put(entityType, allowedIds);
            }
            return allowedIds.contains(entityId.getId());
        }
    }

}
