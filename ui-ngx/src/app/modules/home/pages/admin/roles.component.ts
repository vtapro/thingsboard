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
  scopedPermissions?: { [resource: string]: { [operation: string]: string[] } };
  userIds: string[];
  ownCustomerOnly?: boolean;
}

interface TenantUserInfo {
  id: { id: string };
  email: string;
  firstName?: string;
  lastName?: string;
}

interface RbacEntityGroup {
  id: string;
  name: string;
  entityType: string;
  entityIds: string[];
  description?: string;
  publicGroup?: boolean;
}

interface RbacUserGroup {
  id: string;
  name: string;
  userIds: string[];
  roleIds: string[];
}

interface TenantCustomerInfo {
  id: { id: string };
  title: string;
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
  groups: RbacEntityGroup[] = [];
  userGroups: RbacUserGroup[] = [];
  customers: TenantCustomerInfo[] = [];
  hierarchyRows: Array<{childId: string; parentId: string}> = [];
  readonly hierarchyColumns = ['child', 'parent', 'actions'];
  childCustomerControl = new FormControl('');
  parentCustomerControl = new FormControl('');
  readonly userGroupColumns = ['name', 'userIds', 'roleIds', 'actions'];
  userGroupNameControl = new FormControl('');
  readonly entityTypes = ['DEVICE', 'ASSET', 'ENTITY_VIEW'];
  readonly groupColumns = ['name', 'entityType', 'description', 'publicGroup', 'members', 'actions'];

  groupNameControl = new FormControl('');
  groupTypeControl = new FormControl('DEVICE');
  groupIdsControl = new FormControl('');
  groupDescriptionControl = new FormControl('');
  groupPublicControl = new FormControl(false);

  nameControl = new FormControl('');
  resourceControl = new FormControl('DEVICE');
  groupScopeControl = new FormControl<string[]>([]);
  ownCustomerOnlyControl = new FormControl(false);
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
      this.loadGroups();
      this.loadUserGroups();
      this.loadHierarchy();
    }
  }

  loadUsers() {
    this.http.get<{data: TenantUserInfo[]}>('/api/users?pageSize=100&page=0',
      defaultHttpOptionsFromConfig(undefined)).subscribe(page => this.users = page?.data || []);
  }

  loadGroups() {
    this.http.get<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.groups = settings?.groups || []);
  }

  addGroup() {
    const name = (this.groupNameControl.value || '').trim();
    if (!name) {
      return;
    }
    const entityIds = (this.groupIdsControl.value || '')
      .split(/[\s,;]+/).map(id => id.trim()).filter(id => !!id);
    this.groups = [...this.groups, {
      id: Math.random().toString(36).substring(2, 10),
      name,
      entityType: this.groupTypeControl.value,
      entityIds,
      description: (this.groupDescriptionControl.value || '').trim() || undefined,
      publicGroup: !!this.groupPublicControl.value
    }];
    this.groupNameControl.setValue('');
    this.groupIdsControl.setValue('');
    this.groupDescriptionControl.setValue('');
    this.groupPublicControl.setValue(false);
  }

  removeGroup(group: RbacEntityGroup) {
    this.groups = this.groups.filter(g => g.id !== group.id);
  }

  saveGroups() {
    this.http.post<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup', {groups: this.groups},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.groups = settings?.groups || []);
  }

  loadUserGroups() {
    this.http.get<{groups: RbacUserGroup[]}>('/api/tenant/userGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.userGroups = settings?.groups || []);
  }

  addUserGroup() {
    const name = (this.userGroupNameControl.value || '').trim();
    if (!name) {
      return;
    }
    this.userGroups = [...this.userGroups, {
      id: Math.random().toString(36).substring(2, 10),
      name,
      userIds: [],
      roleIds: []
    }];
    this.userGroupNameControl.setValue('');
  }

  removeUserGroup(group: RbacUserGroup) {
    this.userGroups = this.userGroups.filter(g => g.id !== group.id);
  }

  setUserGroupUsers(group: RbacUserGroup, userIds: string[]) {
    this.userGroups = this.userGroups.map(g => g.id === group.id ? {...g, userIds} : g);
  }

  setUserGroupRoles(group: RbacUserGroup, roleIds: string[]) {
    this.userGroups = this.userGroups.map(g => g.id === group.id ? {...g, roleIds} : g);
  }

  saveUserGroups() {
    this.http.post<{groups: RbacUserGroup[]}>('/api/tenant/userGroup', {groups: this.userGroups},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.userGroups = settings?.groups || []);
  }

  loadHierarchy() {
    this.http.get<{data: TenantCustomerInfo[]}>('/api/customers?pageSize=100&page=0',
      defaultHttpOptionsFromConfig(undefined)).subscribe(page => this.customers = page?.data || []);
    this.http.get<{parents: {[childId: string]: string}}>('/api/tenant/customerHierarchy',
      defaultHttpOptionsFromConfig(undefined)).subscribe(hierarchy => {
      const parents = hierarchy?.parents || {};
      this.hierarchyRows = Object.keys(parents).map(childId => ({childId, parentId: parents[childId]}));
    });
  }

  customerLabel(customerId: string): string {
    const customer = this.customers.find(c => c.id.id === customerId);
    return customer ? customer.title : customerId;
  }

  addHierarchyRow() {
    const childId = this.childCustomerControl.value;
    const parentId = this.parentCustomerControl.value;
    if (!childId || !parentId || childId === parentId) {
      return;
    }
    this.hierarchyRows = [...this.hierarchyRows.filter(row => row.childId !== childId), {childId, parentId}];
    this.persistHierarchy();
  }

  removeHierarchyRow(row: {childId: string; parentId: string}) {
    this.hierarchyRows = this.hierarchyRows.filter(r => r.childId !== row.childId);
    this.persistHierarchy();
  }

  private persistHierarchy() {
    const parents: {[childId: string]: string} = {};
    for (const row of this.hierarchyRows) {
      parents[row.childId] = row.parentId;
    }
    this.http.post('/api/tenant/customerHierarchy', {parents},
      defaultHttpOptionsFromConfig(undefined)).subscribe(() => this.loadHierarchy());
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
    const scopedGroups: string[] = this.groupScopeControl.value || [];
    const permissions: { [resource: string]: string[] } = {};
    const scopedPermissions: { [resource: string]: { [operation: string]: string[] } } = {};
    if (operations.length) {
      if (scopedGroups.length) {
        scopedPermissions[resource] = {};
        for (const operation of operations) {
          scopedPermissions[resource][operation] = scopedGroups;
        }
      } else {
        permissions[resource] = operations;
      }
    }
    this.roles = [...this.roles, {
      id: Math.random().toString(36).substring(2, 10),
      name,
      permissions,
      scopedPermissions,
      userIds: [],
      ownCustomerOnly: !!this.ownCustomerOnlyControl.value
    }];
    this.nameControl.setValue('');
    this.groupScopeControl.setValue([]);
    this.ownCustomerOnlyControl.setValue(false);
  }

  removeRole(role: RbacRole) {
    this.roles = this.roles.filter(r => r.id !== role.id);
  }

  permissionSummary(role: RbacRole): string {
    const global = Object.keys(role.permissions || {})
      .map(resource => `${resource}: ${(role.permissions[resource] || []).join('/')}`)
      .join(', ');
    const scoped = Object.keys(role.scopedPermissions || {})
      .map(resource => {
        const byOperation = role.scopedPermissions[resource] || {};
        return Object.keys(byOperation)
          .map(operation => `${resource}: ${operation} (${(byOperation[operation] || []).length} group(s))`)
          .join(', ');
      })
      .filter(part => !!part)
      .join(', ');
    const own = role.ownCustomerOnly ? 'own customer only' : '';
    return [global, scoped, own].filter(part => !!part).join(', ');
  }

  save() {
    this.http.post<{roles: RbacRole[]}>('/api/tenant/role', {roles: this.roles},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.roles = settings?.roles || []);
  }

}
