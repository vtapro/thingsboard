// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';

/**
 * Explicit access on one entity for a user or a user group. The share is configured by the tenant administrator and
 * is checked before the custom role of the user (see TbRbacAccessControlService).
 */
export interface RbacShare {
  id?: string;
  entityType: string;
  entityId: string;
  assigneeType: 'USER' | 'USER_GROUP';
  assigneeId: string;
  operations: string[];
}

export interface RbacShareSettings {
  shares: RbacShare[];
}

@Injectable({
  providedIn: 'root'
})
export class RbacShareService {

  constructor(private http: HttpClient) {}

  public getShares(): Observable<RbacShareSettings> {
    return this.http.get<RbacShareSettings>('/api/tenant/rbacShare', defaultHttpOptionsFromConfig(undefined));
  }

  public saveShares(settings: RbacShareSettings): Observable<RbacShareSettings> {
    return this.http.post<RbacShareSettings>('/api/tenant/rbacShare', settings,
      defaultHttpOptionsFromConfig({ignoreErrors: true}));
  }

}
