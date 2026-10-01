// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityGroupMember, EntityGroupService } from '@core/http/entity-group.service';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
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
   * Names of the custom roles that grant the permissions of the user (assigned to the user directly or through one
   * of its user groups). An empty list means the platform permissions of its authority apply.
   */
  effectiveRoleNames: string[] = [];
  rolesLoaded = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ManageOwnerAndGroupsDialogData,
              public dialogRef: MatDialogRef<ManageOwnerAndGroupsDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private http: HttpClient,
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
        const roleIds = new Set<string>();
        roles.forEach(role => {
          if (role.userIds && role.userIds.includes(userId)) {
            roleIds.add(role.id);
          }
        });
        this.http.get<{ groups: Array<{ userIds?: string[]; roleIds?: string[] }> }>(
          '/api/tenant/userGroup', options).subscribe({
          next: (userGroups) => {
            (userGroups?.groups || []).forEach(group => {
              if (group.userIds && group.userIds.includes(userId)) {
                (group.roleIds || []).forEach(roleId => roleIds.add(roleId));
              }
            });
            this.effectiveRoleNames = roles.filter(role => roleIds.has(role.id)).map(role => role.name);
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

  get isTenantAdmin(): boolean {
    return this.data.user?.authority === Authority.TENANT_ADMIN;
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
