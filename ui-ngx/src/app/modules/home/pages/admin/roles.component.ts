// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { TranslateService } from '@ngx-translate/core';
import { forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';

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

const MAX_USER_PAGES = 50;

@Component({
    selector: 'tb-roles',
    templateUrl: './roles.component.html',
    styleUrls: ['./roles.component.scss', './settings-card.scss'],
    standalone: false
})
export class RolesComponent extends PageComponent implements OnInit {

  readonly resources = ['DEVICE', 'ASSET', 'DASHBOARD', 'ALARM', 'CUSTOMER', 'ENTITY_VIEW', 'RULE_CHAIN',
    'ADMIN_SETTINGS', 'USER'];

  /**
   * Only devices, assets and entity views may be a member of an entity group, so only their permissions can be
   * scoped to groups (same rule as the backend validation).
   */
  readonly groupScopedResources = ['DEVICE', 'ASSET', 'ENTITY_VIEW'];
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
  ownCustomerOnlyControl = new FormControl(false);

  /**
   * Permissions of the role being created, per entity type (resource). Each entity type is edited in its own tab.
   */
  private permissionDraft: { [resource: string]: { [operation: string]: boolean } } = {};
  private groupScopeDraft: { [resource: string]: string[] } = {};

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private translate: TranslateService) {
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
    this.loadUsersPage(0, [], 0);
  }

  /**
   * Loads every user of the tenant page by page, so that the role can be assigned to any user.
   */
  private loadUsersPage(page: number, users: TenantUserInfo[], loadedPages: number) {
    this.http.get<{data: TenantUserInfo[]; hasNext: boolean}>(
      `/api/users?pageSize=100&page=${page}`, defaultHttpOptionsFromConfig(undefined))
      .subscribe(data => {
        const loaded = users.concat(data?.data || []);
        if (data?.hasNext && loadedPages < MAX_USER_PAGES) {
          this.loadUsersPage(page + 1, loaded, loadedPages + 1);
        } else {
          this.users = loaded;
        }
      });
  }

  loadGroups() {
    this.http.get<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.groups = settings?.groups || [];
      this.loadedGroupIds = this.groups.map(group => group.id);
    });
  }

  /** Ids of the groups loaded from the server, used to delete only the groups removed by the administrator. */
  private loadedGroupIds: string[] = [];

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
    this.http.get<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: current => {
        const byId = new Map<string, RbacEntityGroup>();
        (current?.groups || []).forEach(group => byId.set(group.id, group));
        // The groups edited on this page win over the stored ones, the others are preserved.
        this.groups.forEach(group => byId.set(group.id, group));
        const removedIds = this.loadedGroupIds.filter(id => !this.groups.some(group => group.id === id));
        const saved$ = removedIds.length
          ? forkJoin(removedIds.map(id => this.http.delete(`/api/tenant/entityGroup/${id}`,
              defaultHttpOptionsFromConfig({ignoreErrors: true}))))
          : of(null);
        saved$.pipe(
          switchMap(() => this.http.post<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
            {groups: Array.from(byId.values())}, defaultHttpOptionsFromConfig({ignoreErrors: true})))
        ).subscribe({
          next: settings => {
            this.groups = settings?.groups || [];
            this.loadedGroupIds = this.groups.map(group => group.id);
            this.notifySaved('admin.roles-entity-groups-save-success');
          },
          error: (error: HttpErrorResponse) => {
            this.notifySaveFailed('admin.roles-entity-groups-save-failed', error);
            this.loadGroups();
          }
        });
      },
      error: (error: HttpErrorResponse) => this.notifySaveFailed('admin.roles-entity-groups-save-failed', error)
    });
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
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: settings => {
        this.userGroups = settings?.groups || [];
        this.notifySaved('admin.roles-user-groups-save-success');
      },
      error: (error: HttpErrorResponse) => this.notifySaveFailed('admin.roles-user-groups-save-failed', error)
    });
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
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: () => {
        this.loadHierarchy();
        this.notifySaved('admin.roles-hierarchy-save-success');
      },
      error: (error: HttpErrorResponse) => this.notifySaveFailed('admin.roles-hierarchy-save-failed', error)
    });
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
    const permissions: { [resource: string]: string[] } = {};
    const scopedPermissions: { [resource: string]: { [operation: string]: string[] } } = {};
    for (const resource of this.resources) {
      const operations = this.operations.filter(operation => this.isOperationGranted(resource, operation));
      if (!operations.length) {
        continue;
      }
      const scopedGroups = this.groupsFor(resource);
      if (scopedGroups.length) {
        scopedPermissions[resource] = {};
        for (const operation of operations) {
          scopedPermissions[resource][operation] = scopedGroups;
        }
      } else {
        permissions[resource] = operations;
      }
    }
    if (!Object.keys(permissions).length && !Object.keys(scopedPermissions).length) {
      this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('admin.roles-no-permissions'),
        type: 'warn'
      }));
      return;
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
    this.ownCustomerOnlyControl.setValue(false);
    this.permissionDraft = {};
    this.groupScopeDraft = {};
  }

  isOperationGranted(resource: string, operation: string): boolean {
    return !!this.permissionDraft[resource]?.[operation];
  }

  toggleOperation(resource: string, operation: string) {
    const operations = this.permissionDraft[resource] || (this.permissionDraft[resource] = {});
    operations[operation] = !operations[operation];
    if (!this.hasAnyOperation(resource)) {
      delete this.groupScopeDraft[resource];
    }
  }

  hasAnyOperation(resource: string): boolean {
    const operations = this.permissionDraft[resource];
    return !!operations && this.operations.some(operation => operations[operation]);
  }

  canScopeToGroups(resource: string): boolean {
    return this.groupScopedResources.includes(resource);
  }

  groupsFor(resource: string): string[] {
    return this.groupScopeDraft[resource] || [];
  }

  setGroups(resource: string, groupIds: string[]) {
    if (groupIds?.length) {
      this.groupScopeDraft[resource] = groupIds;
    } else {
      delete this.groupScopeDraft[resource];
    }
  }

  /**
   * Short summary of the operations configured for the entity type, shown as a badge on the tab.
   */
  resourceGrantSummary(resource: string): string {
    const operations = this.permissionDraft[resource];
    if (!operations) {
      return '';
    }
    return this.operations.filter(operation => operations[operation]).join(', ');
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
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: settings => {
        this.roles = settings?.roles || [];
        this.notifySaved('admin.roles-save-success');
      },
      error: (error: HttpErrorResponse) => this.notifySaveFailed('admin.roles-save-failed', error)
    });
  }

  /**
   * Shows the result of a save operation, so that the administrator always knows whether it worked.
   */
  private notifySaved(messageKey: string) {
    this.store.dispatch(new ActionNotificationShow({
      message: this.translate.instant(messageKey),
      type: 'success'
    }));
  }

  private notifySaveFailed(messageKey: string, error: HttpErrorResponse) {
    const details = error?.error?.message;
    const message = this.translate.instant(messageKey);
    this.store.dispatch(new ActionNotificationShow({
      message: details ? `${message}: ${details}` : message,
      type: 'error',
      duration: 5000
    }));
  }

}
