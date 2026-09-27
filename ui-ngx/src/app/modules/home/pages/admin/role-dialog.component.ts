// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { FormControl, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

/** Role of the tenant, as stored by the backend. */
export interface RbacRoleModel {
  id: string;
  name: string;
  permissions: { [resource: string]: string[] };
  scopedPermissions?: { [resource: string]: { [operation: string]: string[] } };
  userIds: string[];
  ownCustomerOnly?: boolean;
  ownOnly?: { [resource: string]: boolean };
}

export interface RoleDialogUser {
  id: { id: string };
  email: string;
  firstName?: string;
  lastName?: string;
}

export interface RoleDialogGroup {
  id: string;
  name: string;
}

export interface RoleDialogData {
  /** Undefined when a new role is created. */
  role?: RbacRoleModel;
  users: RoleDialogUser[];
  resources: string[];
  operationsByResource: { [resource: string]: string[] };
  presets: Array<{ id: string; label: string }>;
  groups: RoleDialogGroup[];
  groupScopedResources: string[];
  ownScopedResources: string[];
}

export interface RoleDialogResult {
  name: string;
  ownCustomerOnly: boolean;
  permissions: { [resource: string]: string[] };
  scopedPermissions: { [resource: string]: { [operation: string]: string[] } };
  ownOnly: { [resource: string]: boolean };
  userIds: string[];
}

/** Operations of the "Self-managed" preset: the user manages the entities created by him/herself. */
const SELF_MANAGED_OPERATIONS = ['CREATE', 'READ', 'WRITE', 'DELETE'];

@Component({
    selector: 'tb-role-dialog',
    templateUrl: './role-dialog.component.html',
    styleUrls: ['./role-dialog.component.scss', './roles-shared.scss'],
    standalone: false
})
export class RoleDialogComponent {

  nameControl = new FormControl('', [Validators.required]);
  ownCustomerOnlyControl = new FormControl(false);
  userIds: string[] = [];

  /** Permissions of the role being edited, per entity type (resource). Each entity type has its own tab. */
  private permissionDraft: { [resource: string]: { [operation: string]: boolean } } = {};
  private ownOnlyDraft: { [resource: string]: boolean } = {};
  private groupScopeDraft: { [resource: string]: string[] } = {};

  constructor(private dialogRef: MatDialogRef<RoleDialogComponent, RoleDialogResult>,
              @Inject(MAT_DIALOG_DATA) public data: RoleDialogData) {
    const role = data?.role;
    if (role) {
      this.nameControl.setValue(role.name);
      this.ownCustomerOnlyControl.setValue(!!role.ownCustomerOnly);
      this.ownOnlyDraft = {...(role.ownOnly || {})};
      this.userIds = [...(role.userIds || [])];
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
    }
  }

  get isEdit(): boolean {
    return !!this.data?.role;
  }

  get resources(): string[] {
    return this.data?.resources || [];
  }

  get presets(): Array<{ id: string; label: string }> {
    return this.data?.presets || [];
  }

  get groups(): RoleDialogGroup[] {
    return this.data?.groups || [];
  }

  get users(): RoleDialogUser[] {
    return this.data?.users || [];
  }

  /** Operations offered for an entity type (fallback: create/read/write/delete). */
  operationsFor(resource: string): string[] {
    return this.data?.operationsByResource?.[resource] || ['CREATE', 'READ', 'WRITE', 'DELETE'];
  }

  /** Human readable label of an operation, e.g. READ_TELEMETRY -> "Read telemetry". */
  operationLabel(operation: string): string {
    const text = operation.replace(/_/g, ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  isOperationGranted(resource: string, operation: string): boolean {
    return !!this.permissionDraft[resource]?.[operation];
  }

  toggleOperation(resource: string, operation: string): void {
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

  /** False while no operation is ticked for any entity type: such a role would not be usable. */
  hasAnyPermission(): boolean {
    return this.resources.some(resource => this.hasAnyOperation(resource));
  }

  /** Applies a preset (Viewer / Operator / Manager / Self-managed / Admin) to the entity type. */
  applyPreset(resource: string, presetId: string): void {
    const available = this.operationsFor(resource);
    const wanted = this.presetOperations(presetId);
    const draft = this.permissionDraft[resource] || (this.permissionDraft[resource] = {});
    if (presetId === 'selfManaged') {
      // PE like "user manages the entities created by him/herself": the basic operations on own entities only.
      for (const operation of available) {
        draft[operation] = SELF_MANAGED_OPERATIONS.includes(operation);
      }
      this.ownOnlyDraft[resource] = true;
      return;
    }
    for (const operation of available) {
      // credentials are sensitive: the presets never grant them, the administrator has to tick them on purpose
      const readAuxiliary = operation.startsWith('READ') && !operation.endsWith('CREDENTIALS');
      draft[operation] = wanted.includes(operation)
        || (presetId === 'operator' && readAuxiliary)
        || (presetId === 'manager' && (readAuxiliary || operation === 'WRITE_TELEMETRY'));
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
    delete this.groupScopeDraft[resource];
  }

  /** "Only my entities" flag of the entity type (owner scope). */
  isOwnOnly(resource: string): boolean {
    return !!this.ownOnlyDraft[resource];
  }

  toggleOwnOnly(resource: string): void {
    this.ownOnlyDraft[resource] = !this.ownOnlyDraft[resource];
  }

  /** True when the owner scope may be configured for the entity type. */
  supportsOwnScope(resource: string): boolean {
    return (this.data?.ownScopedResources || []).includes(resource);
  }

  canScopeToGroups(resource: string): boolean {
    return (this.data?.groupScopedResources || []).includes(resource);
  }

  groupsFor(resource: string): string[] {
    return this.groupScopeDraft[resource] || [];
  }

  setGroups(resource: string, groupIds: string[]): void {
    if (groupIds?.length) {
      this.groupScopeDraft[resource] = groupIds;
    } else {
      delete this.groupScopeDraft[resource];
    }
  }

  userLabel(user: RoleDialogUser): string {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ');
    return name ? `${name} (${user.email})` : user.email;
  }

  cancel(): void {
    this.dialogRef.close();
  }

  save(): void {
    const name = (this.nameControl.value || '').trim();
    if (!name) {
      this.nameControl.markAsTouched();
      return;
    }
    const permissions: { [resource: string]: string[] } = {};
    const scopedPermissions: { [resource: string]: { [operation: string]: string[] } } = {};
    for (const resource of this.resources) {
      const operations = this.operationsFor(resource)
        .filter(operation => this.isOperationGranted(resource, operation));
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
    const ownOnly: { [resource: string]: boolean } = {};
    Object.entries(this.ownOnlyDraft)
      .filter(([resource, on]) => on && this.supportsOwnScope(resource))
      .forEach(([resource]) => ownOnly[resource] = true);
    this.dialogRef.close({
      name,
      ownCustomerOnly: !!this.ownCustomerOnlyControl.value,
      permissions,
      scopedPermissions,
      ownOnly,
      userIds: [...this.userIds]
    });
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

}
