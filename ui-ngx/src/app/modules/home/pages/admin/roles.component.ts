// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { DialogService } from '@core/services/dialog.service';
import { EntityGroupDialogComponent } from '@home/components/entity/entity-group-dialog.component';
import { AddEntitiesDialogComponent } from '@home/components/entity/add-entities-dialog.component';

interface RbacRole {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  scopedPermissions?: { [resource: string]: { [operation: string]: string[] } };
  userIds: string[];
  ownCustomerOnly?: boolean;
  ownOnly?: { [resource: string]: boolean };
}

interface PermissionChip {
  label: string;
  scoped: boolean;
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
  createdTime?: number;
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

const GROUP_ENTITY_TYPES = ['DEVICE', 'ASSET', 'ENTITY_VIEW'];

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
  readonly groupScopedResources = GROUP_ENTITY_TYPES;
  readonly entityTypes = GROUP_ENTITY_TYPES;
  /** Legacy trio, still used for roles created before the detailed matrix. */
  readonly operations = ['READ', 'WRITE', 'DELETE'];

  /**
   * PE like permission matrix: the operations offered for each entity type.
   * Roles that only use READ/WRITE/DELETE keep the legacy behaviour on the backend.
   */
  readonly operationsByResource: { [resource: string]: string[] } = {
    DEVICE: ['CREATE', 'READ', 'WRITE', 'DELETE', 'READ_TELEMETRY', 'WRITE_TELEMETRY',
      'READ_ATTRIBUTES', 'WRITE_ATTRIBUTES', 'READ_CREDENTIALS', 'WRITE_CREDENTIALS',
      'RPC_CALL', 'CLAIM_DEVICES', 'ASSIGN_TO_CUSTOMER'],
    ASSET: ['CREATE', 'READ', 'WRITE', 'DELETE', 'READ_ATTRIBUTES', 'WRITE_ATTRIBUTES', 'ASSIGN_TO_CUSTOMER'],
    ENTITY_VIEW: ['CREATE', 'READ', 'WRITE', 'DELETE', 'READ_TELEMETRY', 'READ_ATTRIBUTES', 'ASSIGN_TO_CUSTOMER'],
    DASHBOARD: ['CREATE', 'READ', 'WRITE', 'DELETE', 'ASSIGN_TO_CUSTOMER'],
    ALARM: ['READ', 'WRITE', 'DELETE'],
    CUSTOMER: ['CREATE', 'READ', 'WRITE', 'DELETE', 'ASSIGN_TO_CUSTOMER'],
    USER: ['CREATE', 'READ', 'WRITE', 'DELETE']
  };

  readonly presets: Array<{ id: string; label: string }> = [
    {id: 'viewer', label: 'Viewer'},
    {id: 'operator', label: 'Operator'},
    {id: 'manager', label: 'Manager'},
    {id: 'admin', label: 'Admin'}
  ];

  /** Operations offered for an entity type (fallback: create/read/write/delete). */
  operationsFor(resource: string): string[] {
    return this.operationsByResource[resource] || ['CREATE', 'READ', 'WRITE', 'DELETE'];
  }

  /** Human readable label of an operation, e.g. READ_TELEMETRY -> "Read telemetry". */
  operationLabel(operation: string): string {
    const text = operation.replace(/_/g, ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  /** Applies a preset (Viewer / Operator / Manager / Admin) to the entity type. */
  applyPreset(resource: string, presetId: string): void {
    const available = this.operationsFor(resource);
    const wanted = this.presetOperations(presetId);
    const draft = this.permissionDraft[resource] || (this.permissionDraft[resource] = {});
    for (const operation of available) {
      draft[operation] = wanted.includes(operation)
        || (presetId === 'operator' && operation.startsWith('READ'))
        || (presetId === 'manager' && (operation.startsWith('READ') || operation === 'WRITE_TELEMETRY'));
    }
  }

  selectAllOperations(resource: string): void {
    const draft = this.permissionDraft[resource] || (this.permissionDraft[resource] = {});
    for (const operation of this.operationsFor(resource)) {
      draft[operation] = true;
    }
  }

  clearAllOperations(resource: string): void {
    this.permissionDraft[resource] = {};
  }

  /** "Only my entities" flag of the entity type (owner scope). */
  isOwnOnly(resource: string): boolean {
    return !!this.ownOnlyDraft[resource];
  }

  /** Loads an existing role into the form so its permissions/users can be edited. */
  editRole(role: RbacRole): void {
    this.editingRoleId = role.id;
    this.nameControl.setValue(role.name);
    this.ownCustomerOnlyControl.setValue(!!role.ownCustomerOnly);
    this.permissionDraft = {};
    this.ownOnlyDraft = {...(role.ownOnly || {})};
    this.groupScopeDraft = {};
    Object.entries(role.permissions || {}).forEach(([resource, operations]) => {
      const draft = this.permissionDraft[resource] = {};
      operations.forEach(operation => draft[operation] = true);
    });
    Object.entries(role.scopedPermissions || {}).forEach(([resource, byOperation]) => {
      const draft = this.permissionDraft[resource] || (this.permissionDraft[resource] = {});
      Object.entries(byOperation).forEach(([operation, groups]) => {
        draft[operation] = true;
        this.groupScopeDraft[resource] = groups as string[];
      });
    });
    if (role.userIds) {
      this.setRoleUsers(role, role.userIds);
    }
  }

  get isEditingRole(): boolean {
    return !!this.editingRoleId;
  }

  cancelEditRole(): void {
    this.editingRoleId = null;
    this.nameControl.setValue('');
    this.permissionDraft = {};
    this.ownOnlyDraft = {};
    this.groupScopeDraft = {};
  }

  toggleOwnOnly(resource: string): void {
    this.ownOnlyDraft[resource] = !this.ownOnlyDraft[resource];
  }

  private presetOperations(presetId: string): string[] {
    switch (presetId) {
      case 'viewer':
        return ['READ'];
      case 'operator':
        return ['READ', 'WRITE_TELEMETRY', 'RPC_CALL'];
      case 'manager':
        return ['CREATE', 'READ', 'WRITE', 'DELETE', 'WRITE_ATTRIBUTES', 'WRITE_TELEMETRY',
          'RPC_CALL', 'CLAIM_DEVICES', 'ASSIGN_TO_CUSTOMER'];
      default:
        return ['CREATE', 'READ', 'WRITE', 'DELETE', 'READ_TELEMETRY', 'WRITE_TELEMETRY',
          'READ_ATTRIBUTES', 'WRITE_ATTRIBUTES', 'READ_CREDENTIALS', 'WRITE_CREDENTIALS',
          'RPC_CALL', 'CLAIM_DEVICES', 'ASSIGN_TO_CUSTOMER'];
    }
  }

  readonly roleColumns = ['name', 'permissions', 'users', 'actions'];
  readonly groupColumns = ['name', 'entityType', 'description', 'public', 'members', 'actions'];
  readonly userGroupColumns = ['name', 'userIds', 'roleIds', 'actions'];
  readonly hierarchyColumns = ['child', 'parent', 'actions'];

  roles: RbacRole[] = [];
  users: TenantUserInfo[] = [];
  groups: RbacEntityGroup[] = [];
  userGroups: RbacUserGroup[] = [];
  customers: TenantCustomerInfo[] = [];
  hierarchyRows: Array<{childId: string; parentId: string}> = [];

  nameControl = new FormControl('');
  ownCustomerOnlyControl = new FormControl(false);
  userGroupNameControl = new FormControl('');
  childCustomerControl = new FormControl('');
  parentCustomerControl = new FormControl('');

  /**
   * Permissions of the role being created, per entity type (resource). Each entity type is edited in its own tab.
   */
  private permissionDraft: { [resource: string]: { [operation: string]: boolean } } = {};
  private ownOnlyDraft: { [resource: string]: boolean } = {};
  /** Id of the role being edited (null = creating a new role). */
  private editingRoleId: string = null;
  private groupScopeDraft: { [resource: string]: string[] } = {};
  private loadedUserGroupIds: string[] = [];

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private translate: TranslateService,
              private dialog: MatDialog,
              private dialogService: DialogService) {
    super();
  }

  ngOnInit() {
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.loadRoles();
      this.loadUsers();
      this.loadGroups();
      this.loadUserGroups();
      this.loadHierarchy();
    }
  }

  /* ------------------------------------------------------------------ roles */

  loadRoles() {
    this.http.get<{roles: RbacRole[]}>('/api/tenant/role', defaultHttpOptionsFromConfig(undefined))
      .subscribe(settings => this.roles = settings?.roles || []);
  }

  addRole() {
    const name = (this.nameControl.value || '').trim();
    if (!name) {
      this.nameControl.markAsTouched();
      return;
    }
    const permissions: { [resource: string]: string[] } = {};
    const scopedPermissions: { [resource: string]: { [operation: string]: string[] } } = {};
    for (const resource of this.resources) {
      const operations = this.operationsFor(resource).filter(operation => this.isOperationGranted(resource, operation));
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
      id: this.generateId(),
      name,
      permissions,
      scopedPermissions,
      userIds: [],
      ...(this.editingRoleId ? {id: this.editingRoleId} : {}),
      ownOnly: Object.fromEntries(Object.entries(this.ownOnlyDraft).filter(e => e[1])),
      ownCustomerOnly: !!this.ownCustomerOnlyControl.value
    }];
    this.nameControl.setValue('');
    this.ownCustomerOnlyControl.setValue(false);
    this.permissionDraft = {};
    this.ownOnlyDraft = {};
    this.editingRoleId = null;
    this.groupScopeDraft = {};
  }

  removeRole(role: RbacRole) {
    this.dialogService.confirm(
      this.translate.instant('admin.roles-delete-title', {name: role.name}),
      this.translate.instant('admin.roles-delete-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        this.roles = this.roles.filter(r => r.id !== role.id);
      }
    });
  }

  setRoleUsers(role: RbacRole, userIds: string[]) {
    this.roles = this.roles.map(r => r.id === role.id ? {...r, userIds} : r);
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
    return !!operations && this.operationsFor(resource).some(operation => operations[operation]);
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
    return this.operationsFor(resource).filter(operation => operations[operation]).join(', ');
  }

  /**
   * Compact representation of the permissions of a role, one chip per entity type.
   */
  permissionChips(role: RbacRole): PermissionChip[] {
    const chips: PermissionChip[] = [];
    for (const resource of Object.keys(role.permissions || {})) {
      const operations = role.permissions[resource] || [];
      if (operations.length) {
        chips.push({label: `${resource} · ${operations.join(', ')}`, scoped: false});
      }
    }
    for (const resource of Object.keys(role.scopedPermissions || {})) {
      const byOperation = role.scopedPermissions[resource] || {};
      const operations = Object.keys(byOperation);
      if (!operations.length) {
        continue;
      }
      const groupIds = new Set<string>();
      operations.forEach(operation => (byOperation[operation] || []).forEach(id => groupIds.add(id)));
      chips.push({
        label: `${resource} · ${operations.join(', ')} · ${this.translate.instant('admin.roles-groups-count',
          {count: groupIds.size})}`,
        scoped: true
      });
    }
    if (role.ownCustomerOnly) {
      chips.push({label: this.translate.instant('admin.roles-own-customer-only'), scoped: true});
    }
    return chips;
  }

  userLabel(user: TenantUserInfo): string {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ');
    return name ? `${name} (${user.email})` : user.email;
  }

  roleUsersLabel(role: RbacRole): string {
    const count = (role.userIds || []).length;
    if (!count) {
      return this.translate.instant('admin.roles-no-users');
    }
    const names = (role.userIds || [])
      .map(id => this.users.find(user => user.id.id === id))
      .filter(user => !!user)
      .map(user => user.email);
    return names.length ? names.join(', ') : this.translate.instant('admin.roles-users-count', {count});
  }

  loadUsers() {
    this.loadUsersPage(0, [], 0);
  }

  /**
   * Loads every user of the tenant page by page, so that a role can be assigned to any user.
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

  /* ---------------------------------------------------------- entity groups */

  loadGroups() {
    this.http.get<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => this.groups = settings?.groups || []);
  }

  addGroup() {
    this.dialog.open(EntityGroupDialogComponent, {
      data: {entityTypes: this.entityTypes},
      width: '480px'
    }).afterClosed().subscribe(value => {
      if (value) {
        this.persistGroup({
          id: this.generateId(),
          name: value.name,
          entityType: value.entityType,
          entityIds: [],
          description: value.description || undefined,
          publicGroup: !!value.publicGroup,
          createdTime: Date.now()
        });
      }
    });
  }

  editGroup(group: RbacEntityGroup) {
    this.dialog.open(EntityGroupDialogComponent, {
      data: {
        name: group.name,
        description: group.description,
        publicGroup: group.publicGroup,
        entityType: group.entityType,
        entityTypes: this.entityTypes
      },
      width: '480px'
    }).afterClosed().subscribe(value => {
      if (value) {
        this.persistGroup({...group, name: value.name, description: value.description || undefined,
          publicGroup: !!value.publicGroup});
      }
    });
  }

  addGroupMembers(group: RbacEntityGroup) {
    this.dialog.open(AddEntitiesDialogComponent, {
      data: {entityType: group.entityType, selectedIds: group.entityIds || []},
      width: '480px'
    }).afterClosed().subscribe((entityIds: string[]) => {
      if (entityIds) {
        this.persistGroup({...group, entityIds});
      }
    });
  }

  removeGroup(group: RbacEntityGroup) {
    this.dialogService.confirm(
      this.translate.instant('entity-group.delete-title', {name: group.name}),
      this.translate.instant('entity-group.delete-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        this.http.delete<{groups: RbacEntityGroup[]}>(`/api/tenant/entityGroup/${group.id}`,
          defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
          next: settings => {
            this.groups = settings?.groups || [];
            this.notifySaved('entity-group.delete-success');
          },
          error: (error: HttpErrorResponse) => {
            this.notifySaveFailed('entity-group.save-failed', error);
            this.loadGroups();
          }
        });
      }
    });
  }

  /**
   * Saves one group without touching the other groups of the tenant.
   */
  private persistGroup(group: RbacEntityGroup) {
    this.http.post<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup/group', group,
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: settings => {
        this.groups = settings?.groups || [];
        this.notifySaved('entity-group.save-success');
      },
      error: (error: HttpErrorResponse) => {
        this.notifySaveFailed('entity-group.save-failed', error);
        this.loadGroups();
      }
    });
  }

  membersLabel(group: RbacEntityGroup): string {
    return String((group.entityIds || []).length);
  }

  /* ------------------------------------------------------------ user groups */

  loadUserGroups() {
    this.http.get<{groups: RbacUserGroup[]}>('/api/tenant/userGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.userGroups = settings?.groups || [];
      this.loadedUserGroupIds = this.userGroups.map(group => group.id);
    });
  }

  addUserGroup() {
    const name = (this.userGroupNameControl.value || '').trim();
    if (!name) {
      this.userGroupNameControl.markAsTouched();
      return;
    }
    this.userGroups = [...this.userGroups, {
      id: this.generateId(),
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
    // Read - modify - write: the groups edited here win, the groups created elsewhere are preserved and only the
    // groups removed by the administrator are deleted.
    this.http.get<{groups: RbacUserGroup[]}>('/api/tenant/userGroup',
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: current => {
        const byId = new Map<string, RbacUserGroup>();
        (current?.groups || []).forEach(group => byId.set(group.id, group));
        this.userGroups.forEach(group => byId.set(group.id, group));
        this.loadedUserGroupIds
          .filter(id => !this.userGroups.some(group => group.id === id))
          .forEach(id => byId.delete(id));
        this.http.post<{groups: RbacUserGroup[]}>('/api/tenant/userGroup', {groups: Array.from(byId.values())},
          defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
          next: settings => {
            this.userGroups = settings?.groups || [];
            this.loadedUserGroupIds = this.userGroups.map(group => group.id);
            this.notifySaved('admin.roles-user-groups-save-success');
          },
          error: (error: HttpErrorResponse) => {
            this.notifySaveFailed('admin.roles-user-groups-save-failed', error);
            this.loadUserGroups();
          }
        });
      },
      error: (error: HttpErrorResponse) => this.notifySaveFailed('admin.roles-user-groups-save-failed', error)
    });
  }

  userGroupUsersLabel(group: RbacUserGroup): string {
    const count = (group.userIds || []).length;
    return count ? this.translate.instant('admin.roles-users-count', {count}) : this.translate.instant('admin.roles-no-users');
  }

  /* ------------------------------------------------------ customer hierarchy */

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

  /* ----------------------------------------------------------------- utils */

  private generateId(): string {
    return Math.random().toString(36).substring(2, 10) + Date.now().toString(36).slice(-4);
  }

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
