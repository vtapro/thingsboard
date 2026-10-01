// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { Observable } from 'rxjs';
import { EntityType } from '@shared/models/entity-type.models';

export interface EntityGroupMember {
  id: string;
  name: string;
  description?: string;
  publicGroup: boolean;
  allGroup: boolean;
  member: boolean;
}

export interface EntityGroupMembers {
  entityId: { id: string; entityType: EntityType };
  groups: EntityGroupMember[];
}

/**
 * Membership of one entity in the entity groups of its entity type, behind the "Manage owner and groups" dialog.
 */
@Injectable({
  providedIn: 'root'
})
export class EntityGroupService {

  constructor(private http: HttpClient) {
  }

  public getMembers(entityType: EntityType, entityId: string, config?: RequestConfig): Observable<EntityGroupMembers> {
    return this.http.get<EntityGroupMembers>(`/api/tenant/entityGroup/members/${entityType}/${entityId}`,
      defaultHttpOptionsFromConfig(config));
  }

  public setMembers(entityType: EntityType, entityId: string, groupIds: string[],
                    config?: RequestConfig): Observable<EntityGroupMembers> {
    return this.http.post<EntityGroupMembers>(`/api/tenant/entityGroup/members/${entityType}/${entityId}`,
      {groupIds}, defaultHttpOptionsFromConfig(config));
  }

}
