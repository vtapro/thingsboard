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

interface EffectiveRole {
  name: string;
  /** One of the two profiles of a customer user. */
  profile: boolean;
  /** Assigned to the user itself (the other roles come from a user group). */
  direct: boolean;
  /** Names of the user groups that carry the role. */
  groups: string[];
}

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

  private loadEffectiveRoles(): void {
    if (this.data.entityType !== EntityType.USER || !this.data.entityId) {
      this.rolesLoaded = true;
      return;
    }
    const userId = this.data.entityId;
    const options = defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true});
    this.http.get<{ roles: Array<{ id: string; name: string; userIds?: string[] }> }>(
      '/api/tenant/role', options).subscribe({
      next: (settings) => {
        const roles = settings?.roles || [];
        this.http.get<{ groups: Array<{ name?: string; userIds?: string[]; roleIds?: string[] }> }>(
          '/api/tenant/userGroup', options).subscribe({
          next: (userGroups) => {
            const roleSources = new Map<string, { names: string[]; direct: boolean }>();
            roles.forEach(role => {
              if (role.userIds && role.userIds.includes(userId)) {
                roleSources.set(role.id, {names: [], direct: true});
              }
            });
            (userGroups?.groups || []).forEach(group => {
              if (group.userIds && group.userIds.includes(userId)) {
                (group.roleIds || []).forEach(roleId => {
                  const source = roleSources.get(roleId) || {names: [], direct: false};
                  if (group.name && !source.names.includes(group.name)) {
                    source.names.push(group.name);
                  }
                  roleSources.set(roleId, source);
                });
              }
            });
            this.effectiveRoles = roles.filter(role => roleSources.has(role.id)).map(role => {
              const source = roleSources.get(role.id);
              return {
                name: role.name,
                profile: CUSTOMER_PROFILE_ROLE_NAMES.includes(role.name),
                direct: source.direct,
                groups: source.names
              };
            });
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
      const options = defaultHttpOptionsFromConfig({});
      this.http.get<{ roles: Array<{ id: string; name: string; userIds?: string[] }> }>(
        '/api/tenant/role', options).subscribe({
        next: (settings) => {
          (settings?.roles || []).forEach(role => {
            if (!CUSTOMER_PROFILE_ROLE_NAMES.includes(role.name) && role.userIds) {
              role.userIds = role.userIds.filter(id => id !== this.data.entityId);
            }
          });
          this.http.post('/api/tenant/role', {roles: settings?.roles || []}, options).subscribe({
            next: () => {
              this.saving = false;
              this.loadEffectiveRoles();
            },
            error: () => {
              this.saving = false;
            }
          });
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
        next: () => this.saveGroups(groupIds),
        error: () => {
          this.saving = false;
          this.formGroup.get('customerId').setErrors({saveFailed: true});
        }
      });
    } else {
      this.saveGroups(groupIds);
    }
  }

  private saveGroups(groupIds: string[]): void {
    this.entityGroupService.setMembers(this.data.entityType, this.data.entityId, groupIds).subscribe({
      next: () => this.dialogRef.close(true),
      error: () => {
        this.saving = false;
      }
    });
  }

}
