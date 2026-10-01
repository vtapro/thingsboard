// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TranslateService } from '@ngx-translate/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityGroupMember, EntityGroupService } from '@core/http/entity-group.service';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { DialogService } from '@core/services/dialog.service';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { UserService } from '@core/http/user.service';
import { EntityType } from '@shared/models/entity-type.models';
import { User } from '@shared/models/user.model';
import { Authority } from '@shared/models/authority.enum';
import { CustomerId } from '@shared/models/id/customer-id';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { isDefinedAndNotNull } from '@core/utils';

export interface ManageOwnerAndGroupsDialogData {
  entityType: EntityType;
  entityId: string;
  entityName: string;
  /**
   * Owner of the entity (the customer that owns it). A tenant administrator has no owner, so the field is hidden.
   */
  ownerId?: string;
  ownerEditable: boolean;
  /**
   * User entity, used to persist the new owner with the standard "save user" API (like ThingsBoard PE).
   */
  user?: User;
}

const CUSTOMER_PROFILE_ROLE_NAMES = ['Customer Administrator', 'Customer User'];

interface RolePermissionSummary {
  resource: string;
  operations: string;
}

/** One role of the tenant, as offered by the "Roles" section of the dialog. */
interface EffectiveRole {
  id: string;
  name: string;
  /** One of the two profiles of a customer user. */
  profile: boolean;
  /** Assigned to the user itself (the roles that only come from a user group can not be unticked here). */
  direct: boolean;
  /** The role applies to the user (directly or through one of its user groups). */
  assigned: boolean;
  /** Names of the user groups that carry the role. */
  groups: string[];
  /** Granted permissions, e.g. `DEVICE create, read`. */
  summary: string;
}

/** `DEVICE create, read` — the granted operations of one role, used by the "Roles" section of the dialog. */
const summarisePermissions = (permissions?: { [resource: string]: string[] },
                             scopedPermissions?: { [resource: string]: { [operation: string]: string[] } }): string => {
  const lines: string[] = [];
  Object.entries(permissions || {}).forEach(([resource, operations]) => {
    if (operations && operations.length) {
      lines.push(`${resource} ${operations.map(op => op.toLowerCase()).join(', ')}`);
    }
  });
  Object.entries(scopedPermissions || {}).forEach(([resource, byOperation]) => {
    const operations = Object.keys(byOperation || {});
    if (operations.length) {
      lines.push(`${resource} ${operations.map(op => op.toLowerCase()).join(', ')} (scoped)`);
    }
  });
  return lines.join(' · ');
};

@Component({
  selector: 'tb-manage-owner-and-groups-dialog',
  templateUrl: './manage-owner-and-groups-dialog.component.html',
  styleUrls: ['./manage-owner-and-groups-dialog.component.scss'],
  standalone: false
})
export class ManageOwnerAndGroupsDialogComponent extends DialogComponent<ManageOwnerAndGroupsDialogComponent, boolean>
  implements OnInit {

  entityType = EntityType;
  groups: EntityGroupMember[] = [];
  groupSelection = new Set<string>();
  formGroup: UntypedFormGroup;
  loading = true;
  saving = false;
  /**
   * Custom roles that grant the permissions of the user (assigned to the user directly or through one of its user
   * groups). An empty list means the platform permissions of its authority apply.
   */
  effectiveRoles: EffectiveRole[] = [];
  /** Roles ticked in the "Roles" section: they are assigned directly to the user. */
  roleSelection = new Set<string>();
  private initialRoleSelection = new Set<string>();
  rolesLoaded = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ManageOwnerAndGroupsDialogData,
              public dialogRef: MatDialogRef<ManageOwnerAndGroupsDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private http: HttpClient,
              private dialogService: DialogService,
              private translate: TranslateService,
              private entityGroupService: EntityGroupService,
              private userService: UserService) {
    super(store, router, dialogRef);
    this.formGroup = this.fb.group({
      customerId: [data.ownerId || null, []]
    });
  }

  ngOnInit(): void {
    this.entityGroupService.getMembers(this.data.entityType, this.data.entityId).subscribe({
      next: (members) => {
        this.groups = members.groups || [];
        this.groups.filter(group => group.member).forEach(group => this.groupSelection.add(group.id));
        this.loading = false;
      },
      error: () => {
        this.groups = [];
        this.loading = false;
      }
    });
    this.loadEffectiveRoles();
  }

  /**
   * Loads the roles of the tenant (with the permissions of each one) and the way they apply to the user: assigned
   * directly to the user or through one of its user groups. The tenant administrator ticks the direct assignment in
   * the "Roles" section of the dialog.
   */
  private loadEffectiveRoles(): void {
    if (this.data.entityType !== EntityType.USER || !this.data.entityId) {
      this.rolesLoaded = true;
      return;
    }
    const userId = this.data.entityId;
    const options = defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true});
    this.http.get<{ roles: Array<{ id: string; name: string;
                                   permissions?: { [resource: string]: string[] };
                                   scopedPermissions?: { [resource: string]: { [operation: string]: string[] } } }> }>(
      '/api/tenant/role', options).subscribe({
      next: (settings) => {
        const roles = settings?.roles || [];
        this.http.get<{ roles: Array<{ id: string; name: string; direct: boolean; assigned: boolean; groups?: string[] }> }>(
          `/api/tenant/user/${userId}/roles`, options).subscribe({
          next: (assignments) => {
            const byId = new Map((assignments?.roles || []).map(role => [role.id, role]));
            this.effectiveRoles = roles.map(role => {
              const assignment = byId.get(role.id);
              return {
                id: role.id,
                name: role.name,
                profile: CUSTOMER_PROFILE_ROLE_NAMES.includes(role.name),
                direct: !!assignment?.direct,
                assigned: !!assignment?.assigned,
                groups: assignment?.groups || [],
                summary: summarisePermissions(role.permissions, role.scopedPermissions)
              };
            });
            this.roleSelection = new Set(this.effectiveRoles.filter(role => role.direct).map(role => role.id));
            this.initialRoleSelection = new Set(this.roleSelection);
            this.rolesLoaded = true;
          },
          error: () => {
            this.rolesLoaded = true;
          }
        });
      },
      error: () => {
        this.rolesLoaded = true;
      }
    });
  }

  /** The roles that only apply through a user group: they follow the group membership of the dialog. */
  get groupRoles(): EffectiveRole[] {
    return this.effectiveRoles.filter(role => !role.direct && role.assigned);
  }

  get roleAssignmentChanged(): boolean {
    if (this.roleSelection.size !== this.initialRoleSelection.size) {
      return true;
    }
    return Array.from(this.roleSelection).some(id => !this.initialRoleSelection.has(id));
  }

  toggleRole(role: EffectiveRole, checked: boolean): void {
    if (checked) {
      this.roleSelection.add(role.id);
    } else {
      this.roleSelection.delete(role.id);
    }
  }

  isRoleSelected(role: EffectiveRole): boolean {
    return this.roleSelection.has(role.id);
  }

  /** A tenant administrator belongs to the tenant, so it has no owner customer (the field is not applicable). */
  get ownerNotApplicable(): boolean {
    return !this.data.ownerEditable;
  }

  /** True when every group of the list is maintained by the platform, so nothing is editable here. */
  get onlySystemGroups(): boolean {
    return this.groups.length > 0 && this.groups.every(group => group.system);
  }

  /** True when the two profiles of a customer user (Customer Administrators / Customer Users) are available. */
  get hasCustomerProfileGroups(): boolean {
    return this.data.entityType === EntityType.USER && this.groups.some(group =>
      group.name === 'Customer Administrators' || group.name === 'Customer Users');
  }

  get isTenantAdmin(): boolean {
    return this.data.user?.authority === Authority.TENANT_ADMIN;
  }

  /** Roles that add permissions on top of the profile of the customer user (they are not the profile itself). */
  get extraRoles(): EffectiveRole[] {
    return this.effectiveRoles.filter(role => !role.profile);
  }

  /** True when the user is a plain customer user, so any extra role adds permissions to a read-only profile. */
  get isPlainCustomerProfile(): boolean {
    return this.groups.some(group => group.member && group.name === 'Customer Users');
  }

  get extraRoleNames(): string {
    return this.extraRoles.map(role => role.name).join(', ');
  }

  /**
   * Drops the direct assignment of the user to the roles that are not one of the two customer user profiles, so the
   * permissions of the user are exactly the ones of its groups (the profile). The roles itself and the assignments of
   * the other users are not touched.
   */
  removeExtraRoles(): void {
    if (!this.extraRoles.length || this.saving) {
      return;
    }
    this.dialogService.confirm(
      this.translate.instant('owner-and-groups.remove-extra-roles-title'),
      this.translate.instant('owner-and-groups.remove-extra-roles-text', {roles: this.extraRoleNames}),
      this.translate.instant('action.cancel'),
      this.translate.instant('owner-and-groups.remove-extra-roles'),
      true
    ).subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.saving = true;
      // keep only the profile roles the user has directly: the assignment of every other role is dropped
      const roleIds = this.effectiveRoles.filter(role => role.direct && role.profile).map(role => role.id);
      this.http.post(`/api/tenant/user/${this.data.entityId}/roles`, {roleIds},
        defaultHttpOptionsFromConfig({})).subscribe({
        next: () => {
          this.saving = false;
          this.loadEffectiveRoles();
        },
        error: () => {
          this.saving = false;
        }
      });
    });
  }

  isMember(group: EntityGroupMember): boolean {
    return group.allGroup || this.groupSelection.has(group.id);
  }

  isEditable(group: EntityGroupMember): boolean {
    return !group.system;
  }

  toggleGroup(group: EntityGroupMember, checked: boolean): void {
    if (!this.isEditable(group)) {
      return;
    }
    if (checked) {
      this.groupSelection.add(group.id);
    } else {
      this.groupSelection.delete(group.id);
    }
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  save(): void {
    if (this.saving) {
      return;
    }
    this.saving = true;
    const groupIds = this.groups.filter(group => this.groupSelection.has(group.id)).map(group => group.id);
    const ownerChanged = this.data.ownerEditable && this.data.user
      && (this.data.ownerId || null) !== (this.formGroup.get('customerId').value || null);
    if (ownerChanged) {
      const user: User = {...this.data.user};
      const customerId = this.formGroup.get('customerId').value;
      user.customerId = isDefinedAndNotNull(customerId) ? new CustomerId(customerId) : null;
      this.userService.saveUser(user).subscribe({
        next: () => this.saveRoles(groupIds),
        error: () => {
          this.saving = false;
          this.formGroup.get('customerId').setErrors({saveFailed: true});
        }
      });
    } else {
      this.saveRoles(groupIds);
    }
  }

  /** Persists the direct role assignment of the user (only when the ticks of the "Roles" section changed). */
  private saveRoles(groupIds: string[]): void {
    if (this.data.entityType !== EntityType.USER || !this.roleAssignmentChanged) {
      this.saveGroups(groupIds);
      return;
    }
    const roleIds = Array.from(this.roleSelection);
    this.http.post(`/api/tenant/user/${this.data.entityId}/roles`, {roleIds},
      defaultHttpOptionsFromConfig({})).subscribe({
      next: () => this.saveGroups(groupIds),
      error: () => this.saveFailed('owner-and-groups.save-roles-failed')
    });
  }

  private saveGroups(groupIds: string[]): void {
    this.entityGroupService.setMembers(this.data.entityType, this.data.entityId, groupIds).subscribe({
      next: () => this.dialogRef.close(true),
      error: () => this.saveFailed('owner-and-groups.save-groups-failed')
    });
  }

  /**
   * A step of the dialog failed after an earlier step was already applied: tell the administrator which step failed
   * and reload the state of the dialog, so what is shown is what the platform really stored.
   */
  private saveFailed(messageKey: string): void {
    this.saving = false;
    this.store.dispatch(new ActionNotificationShow({
      message: this.translate.instant(messageKey),
      type: 'error'
    }));
    this.loadEffectiveRoles();
  }

}
