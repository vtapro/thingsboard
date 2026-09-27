// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, Resolve } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';

export interface EntityGroupInfo {
  id: string;
  name: string;
  entityType: string;
  entityIds: string[];
  allGroup?: boolean;
}

/**
 * Resolves the entity group of the group detail page, so the page (and its breadcrumb) has the group name and its
 * members as soon as the route is activated.
 */
@Injectable({
  providedIn: 'root'
})
export class EntityGroupResolver implements Resolve<EntityGroupInfo> {

  constructor(private http: HttpClient) {}

  resolve(route: ActivatedRouteSnapshot): Observable<EntityGroupInfo> {
    const groupId = route.params.groupId;
    const entityType = route.data.entityType;
    return this.http.get<{ groups: EntityGroupInfo[] }>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).pipe(
      map(settings => (settings?.groups || []).find(group => group.id === groupId
        && (!entityType || group.entityType === entityType)) || null)
    );
  }

}
