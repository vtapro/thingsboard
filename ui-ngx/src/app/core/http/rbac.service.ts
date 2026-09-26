// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { setRbacPermissions } from '@core/services/rbac-permissions';

interface RbacRole {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  scopedPermissions?: { [resource: string]: { [operation: string]: string[] } };
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
      const addOperation = (resource: string, operation: string) => {
        const operations = merged[resource] || (merged[resource] = []);
        if (!operations.includes(operation)) {
          operations.push(operation);
        }
      };
      for (const role of roles) {
        for (const [resource, operations] of Object.entries(role.permissions || {})) {
          (operations || []).forEach(operation => addOperation(resource, operation));
        }
        // a role may be limited to entity groups: its operations are stored per operation -> group ids
        for (const [resource, byOperation] of Object.entries(role.scopedPermissions || {})) {
          Object.entries(byOperation || {}).forEach(([operation, groups]) => {
            if (groups && groups.length) {
              addOperation(resource, operation);
            }
          });
        }
      }
      setRbacPermissions(Object.keys(merged).length ? merged : null);
    });
  }

}
