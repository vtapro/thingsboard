// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { Store } from '@ngrx/store';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
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
import { RoleDialogComponent, RoleDialogResult } from '@home/pages/admin/role-dialog.component';
import { UserGroupDialogComponent, UserGroupDialogResult } from '@home/pages/admin/user-group-dialog.component';
import { CustomerHierarchyDialogComponent, CustomerHierarchyRelation } from
    '@home/pages/admin/customer-hierarchy-dialog.component';

interface RbacRole {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  scopedPermissions?: { [resource: string]: { [operation: string]: string[] } };
  userIds: string[];
  ownCustomerOnly?: boolean;
  ownOnly?: { [resource: string]: boolean };
  /** Role cua nen tang (2 profile customer user): khong the xoa/doi ten. */
  system?: boolean;
}

interface PermissionChip {
  /** Compact label, e.g. `DEVICE · create, read +8`. */
  label: string;
  /** Full list of the granted operations, shown on hover. */
  tooltip: string;
  scoped: boolean;
}

/** Number of operations shown in the compact chip before the `+N` counter. */
const CHIP_OPERATIONS = 2;

const compactOperations = (operations: string[]): string => operations
  .slice(0, CHIP_OPERATIONS)
  .map(operation => operation.toLowerCase())
  .join(', ') + (operations.length > CHIP_OPERATIONS ? ` +${operations.length - CHIP_OPERATIONS}` : '');

interface TenantUserInfo {
  id: { id: string };
  email: string;
  firstName?: string;
  lastName?: string;
  authority?: string;
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
const MAX_CUSTOMER_PAGES = 50;

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
  // entity groups now also exist for the members (users) and for the customers, like in ThingsBoard PE
  readonly groupScopedResources = [...GROUP_ENTITY_TYPES, 'USER', 'CUSTOMER'];
  readonly entityTypes = [...GROUP_ENTITY_TYPES, 'USER', 'CUSTOMER'];
  /**
   * Resources a user may create itself, so the "only entities created by the user" scope is meaningful. For the
   * members (users and customers) this is what lets a manager add the members of its own team.
   */
  readonly ownScopedResources = [...GROUP_ENTITY_TYPES, 'USER', 'CUSTOMER'];

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
    {id: 'selfManaged', label: 'Self-managed'},
    {id: 'admin', label: 'Admin'}
  ];

  readonly roleColumns = ['name', 'permissions', 'users', 'actions'];
  readonly groupColumns = ['name', 'entityType', 'description', 'public', 'members', 'actions'];
  readonly userGroupColumns = ['name', 'userIds', 'roleIds', 'actions'];
  readonly hierarchyColumns = ['child', 'parent', 'actions'];
  readonly pageSizeOptions = [10, 20, 50];

  /** Paging of the entity groups, the user groups and the customer hierarchy tables. */
  rolePageIndex = 0;
  rolePageSize = 10;
  groupPageIndex = 0;
  groupPageSize = 10;
  userGroupPageIndex = 0;
  userGroupPageSize = 10;
  hierarchyPageIndex = 0;
  hierarchyPageSize = 10;

  /** Free text used to filter the user (and role) selects of the user groups tab. */
  userGroupUserSearch = '';
  userGroupRoleSearch = '';

  roles: RbacRole[] = [];
  users: TenantUserInfo[] = [];
  groups: RbacEntityGroup[] = [];
  userGroups: RbacUserGroup[] = [];
  customers: TenantCustomerInfo[] = [];
  hierarchyRows: Array<{childId: string; parentId: string}> = [];

  private loadedUserGroupIds: string[] = [];

  /** Queue of the user group saves: they are read - modify - write cycles, so they must not overlap. */
  private userGroupSaveQueue: Promise<void> = Promise.resolve();

  /**
   * Users that can not be a member of a role: the tenant administrators (and the system administrators) always
   * keep the full set of permissions of the platform, a role is meant for the users of the tenant only.
   */
  private nonAssignableUserIds = new Set<string>();

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
      .subscribe(settings => {
      this.roles = settings?.roles || [];
      this.clampRolePage();
    });
  }

  /** Rows of the current page of the roles table. */
  get pagedRoles(): RbacRole[] {
    const start = this.rolePageIndex * this.rolePageSize;
    return this.roles.slice(start, start + this.rolePageSize);
  }

  onRolePageChange(event: PageEvent): void {
    this.rolePageIndex = event.pageIndex;
    this.rolePageSize = event.pageSize;
  }

  private clampRolePage(): void {
    this.rolePageIndex = this.clampPageIndex(this.rolePageIndex, this.roles.length, this.rolePageSize);
  }

  /** Opens the role dialog (without a role = create a new one), then persists the whole list of roles. */
  addRole() {
    this.openRoleDialog();
  }

  editRole(role: RbacRole) {
    this.openRoleDialog(role);
  }

  private openRoleDialog(role?: RbacRole) {
    this.dialog.open<RoleDialogComponent, any, RoleDialogResult>(RoleDialogComponent, {
      data: {
        // never show a (legacy) tenant administrator assignment in the dialog
        role: role
          ? {...role, userIds: (role.userIds || []).filter(id => !this.nonAssignableUserIds.has(id))}
          : undefined,
        users: this.users,
        resources: this.resources,
        operationsByResource: this.operationsByResource,
        presets: this.presets,
        groups: this.groups,
        groupScopedResources: this.groupScopedResources,
        ownScopedResources: this.ownScopedResources
      },
      width: '1080px',
      maxWidth: '94vw',
      maxHeight: '92vh',
      autoFocus: false
    }).afterClosed().subscribe((result: RoleDialogResult) => {
      if (!result) {
        return;
      }
      if (!Object.keys(result.permissions).length && !Object.keys(result.scopedPermissions).length) {
        this.store.dispatch(new ActionNotificationShow({
          message: this.translate.instant('admin.roles-no-permissions'),
          type: 'warn'
        }));
        return;
      }
      const savedRole: RbacRole = {
        id: role?.id || this.generateId(),
        name: result.name,
        permissions: result.permissions,
        scopedPermissions: result.scopedPermissions,
        ownOnly: result.ownOnly,
        ownCustomerOnly: result.ownCustomerOnly,
        // a tenant administrator is never a member of a role (the dialog does not offer them either)
        userIds: (result.userIds || []).filter(id => !this.nonAssignableUserIds.has(id))
      };
      const roles = role
        ? this.roles.map(r => r.id === role.id ? savedRole : r)
        : [...this.roles, savedRole];
      this.saveRoles(roles);
    });
  }

  removeRole(role: RbacRole) {
    if (role.system) {
      this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('admin.roles-system-role'),
        type: 'warn'
      }));
      return;
    }
    this.dialogService.confirm(
      this.translate.instant('admin.roles-delete-title', {name: role.name}),
      this.translate.instant('admin.roles-delete-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        this.saveRoles(this.roles.filter(r => r.id !== role.id));
      }
    });
  }

  /** Persists the given list of roles (the backend stores the roles of the tenant as a whole). */
  private saveRoles(roles: RbacRole[]) {
    this.http.post<{roles: RbacRole[]}>('/api/tenant/role', {roles},
      defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
      next: settings => {
        this.roles = settings?.roles || roles;
        this.clampRolePage();
        this.notifySaved('admin.roles-save-success');
      },
      error: (error: HttpErrorResponse) => {
        this.notifySaveFailed('admin.roles-save-failed', error);
        this.loadRoles();
      }
    });
  }

  /**
   * Compact representation of the permissions of a role, one chip per entity type.
   */
  permissionChips(role: RbacRole): PermissionChip[] {
    const chips: PermissionChip[] = [];
    for (const resource of Object.keys(role.permissions || {})) {
      const operations = role.permissions[resource] || [];
      if (operations.length) {
        chips.push({
          label: `${resource} · ${compactOperations(operations)}${this.ownScopeSuffix(role, resource)}`,
          tooltip: `${resource}: ${operations.join(', ')}`,
          scoped: false
        });
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
        label: `${resource} · ${compactOperations(operations)} · ${this.translate.instant('admin.roles-groups-count',
          {count: groupIds.size})}${this.ownScopeSuffix(role, resource)}`,
        tooltip: `${resource}: ${operations.join(', ')} · ${this.translate.instant('admin.roles-groups-count',
          {count: groupIds.size})}`,
        scoped: true
      });
    }
    if (role.ownCustomerOnly) {
      const label = this.translate.instant('admin.roles-own-customer-only');
      chips.push({label, tooltip: label, scoped: true});
    }
    return chips;
  }

  /** Marks a permission chip when the role is limited to the entities created by the user itself. */
  private ownScopeSuffix(role: RbacRole, resource: string): string {
    return role.ownOnly && role.ownOnly[resource]
      ? ' · ' + this.translate.instant('admin.roles-own-chip')
      : '';
  }

  userLabel(user: TenantUserInfo): string {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ');
    return name ? `${name} (${user.email})` : user.email;
  }

  roleUsersLabel(role: RbacRole): string {
    // tenant administrators are not members of a role, ignore the (old) assignments of them
    const userIds = (role.userIds || []).filter(id => !this.nonAssignableUserIds.has(id));
    const count = userIds.length;
    if (!count) {
      return this.translate.instant('admin.roles-no-users');
    }
    const names = userIds
      .map(id => this.users.find(user => user.id.id === id))
      .filter(user => !!user)
      .map(user => user.email);
    return names.length ? names.join(', ') : this.translate.instant('admin.roles-users-count', {count});
  }

  /** Users matching the search box of the user select of the user groups tab. */
  filteredUsers(): TenantUserInfo[] {
    const term = (this.userGroupUserSearch || '').trim().toLowerCase();
    if (!term) {
      return this.users;
    }
    return this.users.filter(user => this.userLabel(user).toLowerCase().includes(term));
  }

  /** Roles matching the search box of the role select of the user groups tab. */
  filteredRoles(): RbacRole[] {
    const term = (this.userGroupRoleSearch || '').trim().toLowerCase();
    if (!term) {
      return this.roles;
    }
    return this.roles.filter(role => role.name.toLowerCase().includes(term));
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
          this.nonAssignableUserIds = new Set(loaded
            .filter(user => user.authority === 'TENANT_ADMIN' || user.authority === 'SYS_ADMIN')
            .map(user => user.id.id));
          this.users = loaded.filter(user => !this.nonAssignableUserIds.has(user.id.id));
        }
      });
  }

  /* ---------------------------------------------------------- entity groups */

  loadGroups() {
    this.http.get<{groups: RbacEntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.groups = settings?.groups || [];
      this.clampGroupPage();
    });
  }

  /** Rows of the current page of the entity groups table. */
  get pagedGroups(): RbacEntityGroup[] {
    const start = this.groupPageIndex * this.groupPageSize;
    return this.groups.slice(start, start + this.groupPageSize);
  }

  onGroupPageChange(event: PageEvent): void {
    this.groupPageIndex = event.pageIndex;
    this.groupPageSize = event.pageSize;
  }

  private clampGroupPage(): void {
    this.groupPageIndex = this.clampPageIndex(this.groupPageIndex, this.groups.length, this.groupPageSize);
  }

  /** Keeps a page index valid after rows were added or removed. */
  private clampPageIndex(pageIndex: number, length: number, pageSize: number): number {
    const lastPage = Math.max(0, Math.ceil(length / pageSize) - 1);
    return Math.min(pageIndex, lastPage);
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
            this.clampGroupPage();
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
        this.clampGroupPage();
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
      this.clampUserGroupPage();
    });
  }

  /** Rows of the current page of the user groups table. */
  get pagedUserGroups(): RbacUserGroup[] {
    const start = this.userGroupPageIndex * this.userGroupPageSize;
    return this.userGroups.slice(start, start + this.userGroupPageSize);
  }

  onUserGroupPageChange(event: PageEvent): void {
    this.userGroupPageIndex = event.pageIndex;
    this.userGroupPageSize = event.pageSize;
  }

  private clampUserGroupPage(): void {
    this.userGroupPageIndex = this.clampPageIndex(this.userGroupPageIndex, this.userGroups.length,
      this.userGroupPageSize);
  }

  addUserGroup() {
    this.dialog.open<UserGroupDialogComponent, any, UserGroupDialogResult>(UserGroupDialogComponent, {
      width: '420px',
      autoFocus: false
    }).afterClosed().subscribe((result: UserGroupDialogResult) => {
      if (result) {
        this.persistUserGroups([...this.userGroups, {
          id: this.generateId(),
          name: result.name,
          userIds: [],
          roleIds: []
        }]);
      }
    });
  }

  editUserGroup(group: RbacUserGroup) {
    this.dialog.open<UserGroupDialogComponent, any, UserGroupDialogResult>(UserGroupDialogComponent, {
      data: {name: group.name},
      width: '420px',
      autoFocus: false
    }).afterClosed().subscribe((result: UserGroupDialogResult) => {
      if (result) {
        this.persistUserGroups(this.userGroups.map(g => g.id === group.id ? {...g, name: result.name} : g));
      }
    });
  }

  removeUserGroup(group: RbacUserGroup) {
    this.dialogService.confirm(
      this.translate.instant('admin.roles-delete-user-group-title', {name: group.name}),
      this.translate.instant('admin.roles-delete-user-group-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        // the group is mirrored into an entity group of the user type (the "Manage owner and groups" dialog edits its
        // members), so delete that group as well, otherwise it would show up again on the next read
        const entityGroup = this.groups.find(g => g.entityType === 'USER' && g.name === group.name);
        if (entityGroup) {
          this.http.delete<{groups: RbacEntityGroup[]}>(`/api/tenant/entityGroup/${entityGroup.id}`,
            defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
            next: settings => {
              this.groups = settings?.groups || this.groups.filter(g => g.id !== entityGroup.id);
              this.clampGroupPage();
              this.persistUserGroups(this.userGroups.filter(g => g.id !== group.id));
            },
            error: (error: HttpErrorResponse) => this.notifySaveFailed('entity-group.save-failed', error)
          });
        } else {
          this.persistUserGroups(this.userGroups.filter(g => g.id !== group.id));
        }
      }
    });
  }

  setUserGroupUsers(group: RbacUserGroup, userIds: string[]) {
    this.persistUserGroups(this.userGroups.map(g => g.id === group.id ? {...g, userIds} : g));
  }

  setUserGroupRoles(group: RbacUserGroup, roleIds: string[]) {
    this.persistUserGroups(this.userGroups.map(g => g.id === group.id ? {...g, roleIds} : g));
  }

  /**
   * Persists the user groups of the tenant (read - modify - write: the groups edited here win, the groups created
   * elsewhere are preserved and only the groups removed by the administrator are deleted).
   *
   * The saves are queued: two of them running at the same time would read the same state and the second one would
   * overwrite the first one (and bring back the groups it deleted).
   */
  private persistUserGroups(groups: RbacUserGroup[]) {
    // optimistic local state: the table shows the change at once, the queued save confirms it
    this.userGroups = groups;
    this.clampUserGroupPage();
    this.userGroupSaveQueue = this.userGroupSaveQueue.then(() => this.saveUserGroups(groups));
  }

  private saveUserGroups(groups: RbacUserGroup[]): Promise<void> {
    return new Promise<void>(resolve => {
      this.http.get<{groups: RbacUserGroup[]}>('/api/tenant/userGroup',
        defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
        next: current => {
          const byId = new Map<string, RbacUserGroup>();
          (current?.groups || []).forEach(group => byId.set(group.id, group));
          groups.forEach(group => byId.set(group.id, group));
          this.loadedUserGroupIds
            .filter(id => !groups.some(group => group.id === id))
            .forEach(id => byId.delete(id));
          this.http.post<{groups: RbacUserGroup[]}>('/api/tenant/userGroup', {groups: Array.from(byId.values())},
            defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe({
            next: settings => {
              this.userGroups = settings?.groups || groups;
              this.loadedUserGroupIds = this.userGroups.map(group => group.id);
              this.clampUserGroupPage();
              this.notifySaved('admin.roles-user-groups-save-success');
              resolve();
            },
            error: (error: HttpErrorResponse) => {
              this.notifySaveFailed('admin.roles-user-groups-save-failed', error);
              this.loadUserGroups();
              resolve();
            }
          });
        },
        error: (error: HttpErrorResponse) => {
          this.notifySaveFailed('admin.roles-user-groups-save-failed', error);
          resolve();
        }
      });
    });
  }

  userGroupUsersLabel(group: RbacUserGroup): string {
    const count = (group.userIds || []).length;
    return count ? this.translate.instant('admin.roles-users-count', {count}) : this.translate.instant('admin.roles-no-users');
  }

  /* ------------------------------------------------------ customer hierarchy */

  loadHierarchy() {
    this.loadCustomers();
    this.http.get<{parents: {[childId: string]: string}}>('/api/tenant/customerHierarchy',
      defaultHttpOptionsFromConfig(undefined)).subscribe(hierarchy => {
      const parents = hierarchy?.parents || {};
      this.hierarchyRows = Object.keys(parents).map(childId => ({childId, parentId: parents[childId]}));
      this.clampHierarchyPage();
    });
  }

  /** Loads every customer of the tenant page by page: the hierarchy dialog offers all of them. */
  private loadCustomers(page = 0, customers: TenantCustomerInfo[] = []): void {
    this.http.get<{data: TenantCustomerInfo[]; hasNext: boolean}>(
      `/api/customers?pageSize=100&page=${page}`, defaultHttpOptionsFromConfig(undefined))
      .subscribe(data => {
        const loaded = customers.concat(data?.data || []);
        if (data?.hasNext && page + 1 < MAX_CUSTOMER_PAGES) {
          this.loadCustomers(page + 1, loaded);
        } else {
          this.customers = loaded;
        }
      });
  }

  /** Rows of the current page of the customer hierarchy table. */
  get pagedHierarchyRows(): Array<{childId: string; parentId: string}> {
    const start = this.hierarchyPageIndex * this.hierarchyPageSize;
    return this.hierarchyRows.slice(start, start + this.hierarchyPageSize);
  }

  onHierarchyPageChange(event: PageEvent): void {
    this.hierarchyPageIndex = event.pageIndex;
    this.hierarchyPageSize = event.pageSize;
  }

  private clampHierarchyPage(): void {
    this.hierarchyPageIndex = this.clampPageIndex(this.hierarchyPageIndex, this.hierarchyRows.length,
      this.hierarchyPageSize);
  }

  customerLabel(customerId: string): string {
    const customer = this.customers.find(c => c.id.id === customerId);
    return customer ? customer.title : customerId;
  }

  addHierarchyRow() {
    this.openHierarchyDialog();
  }

  editHierarchyRow(row: {childId: string; parentId: string}) {
    this.openHierarchyDialog(row);
  }

  private openHierarchyDialog(row?: {childId: string; parentId: string}) {
    this.dialog.open<CustomerHierarchyDialogComponent, any, CustomerHierarchyRelation>(
      CustomerHierarchyDialogComponent, {
        data: {
          customers: this.customers.map(customer => ({id: customer.id.id, title: customer.title})),
          rows: this.hierarchyRows.map(relation => ({...relation})),
          row
        },
        width: '520px',
        autoFocus: false
      }).afterClosed().subscribe((result: CustomerHierarchyRelation) => {
      if (!result) {
        return;
      }
      this.hierarchyRows = [
        ...this.hierarchyRows.filter(relation => relation.childId !== result.childId),
        {childId: result.childId, parentId: result.parentId}
      ];
      this.persistHierarchy();
    });
  }

  removeHierarchyRow(row: {childId: string; parentId: string}) {
    this.dialogService.confirm(
      this.translate.instant('admin.roles-delete-relation-title', {customer: this.customerLabel(row.childId)}),
      this.translate.instant('admin.roles-delete-relation-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        this.hierarchyRows = this.hierarchyRows.filter(r => r.childId !== row.childId);
        this.persistHierarchy();
      }
    });
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
