// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';

interface RbacRole {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  userIds: string[];
}

interface TenantUserInfo {
  id: { id: string };
  email: string;
  firstName?: string;
  lastName?: string;
}

@Component({
    selector: 'tb-roles',
    templateUrl: './roles.component.html',
    styleUrls: ['./roles.component.scss', './settings-card.scss'],
    standalone: false
})
export class RolesComponent extends PageComponent implements OnInit {

  readonly resources = ['DEVICE', 'ASSET', 'DASHBOARD', 'ALARM', 'CUSTOMER', 'ENTITY_VIEW', 'RULE_CHAIN'];
  readonly operations = ['READ', 'WRITE', 'DELETE'];
  readonly displayedColumns = ['name', 'permissions', 'users', 'actions'];

  roles: RbacRole[] = [];
  users: TenantUserInfo[] = [];

  nameControl = new FormControl('');
  resourceControl = new FormControl('DEVICE');
  operationControls: { [operation: string]: FormControl } = {
    READ: new FormControl(true),
    WRITE: new FormControl(false),
    DELETE: new FormControl(false)
  };

  constructor(protected store: Store<AppState>,
              private http: HttpClient) {
    super();
  }

  ngOnInit() {
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.load();
      this.loadUsers();
    }
  }

  loadUsers() {
    this.http.get<{data: TenantUserInfo[]}>('/api/users?pageSize=100&page=0',
      defaultHttpOptionsFromConfig(undefined)).subscribe(page => this.users = page?.data || []);
  }

  setRoleUsers(role: RbacRole, userIds: string[]) {
    this.roles = this.roles.map(r => r.id === role.id ? {...r, userIds} : r);
  }

  userLabel(user: TenantUserInfo): string {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ');
    return name ? `${name} (${user.email})` : user.email;
  }

  load() {
    this.http.get<{roles: RbacRole[]}>('/api/tenant/role', defaultHttpOptionsFromConfig(undefined))
      .subscribe(settings => this.roles = settings?.roles || []);
  }

  addRole() {
    const name = (this.nameControl.value || '').trim();
    if (!name) {
      return;
    }
    const resource = this.resourceControl.value;
    const operations = this.operations.filter(op => this.operationControls[op].value);
    this.roles = [...this.roles, {
      id: Math.random().toString(36).substring(2, 10),
      name,
      permissions: operations.length ? {[resource]: operations} : {},
      userIds: []
    }];
    this.nameControl.setValue('');
  }

  removeRole(role: RbacRole) {
    this.roles = this.roles.filter(r => r.id !== role.id);
  }

  permissionSummary(role: RbacRole): string {
    return Object.keys(role.permissions || {})
      .map(resource => `${resource}: ${(role.permissions[resource] || []).join('/')}`)
      .join(', ');
  }

  save() {
    this.http.post<{roles: RbacRole[]}>('/api/tenant/role', {roles: this.roles},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.roles = settings?.roles || []);
  }

}
