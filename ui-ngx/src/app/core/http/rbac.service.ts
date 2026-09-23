// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { setRbacPermissions } from '@core/services/menu.models';

interface RbacRole {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  userIds: string[];
}

@Injectable({
  providedIn: 'root'
})
export class RbacService {

  constructor(private http: HttpClient) {}

  /**
   * Loads roles of the current user and merges their permissions.
   * When the user has no custom role the platform default matrix is used (no filtering).
   */
  public loadUserRoles(): void {
    this.http.get<RbacRole[]>('/api/user/roles',
      defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true})).subscribe(roles => {
      if (!roles || !roles.length) {
        setRbacPermissions(null);
        return;
      }
      const merged: { [resource: string]: string[] } = {};
      for (const role of roles) {
        const permissions = role.permissions || {};
        for (const resource of Object.keys(permissions)) {
          const operations = merged[resource] || [];
          for (const operation of permissions[resource] || []) {
            if (!operations.includes(operation)) {
              operations.push(operation);
            }
          }
          merged[resource] = operations;
        }
      }
      setRbacPermissions(Object.keys(merged).length ? merged : null);
    });
  }

}

