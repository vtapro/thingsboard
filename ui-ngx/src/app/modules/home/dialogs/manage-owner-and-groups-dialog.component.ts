// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityGroupMember, EntityGroupService } from '@core/http/entity-group.service';
import { UserService } from '@core/http/user.service';
import { EntityType } from '@shared/models/entity-type.models';
import { User } from '@shared/models/user.model';
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

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ManageOwnerAndGroupsDialogData,
              public dialogRef: MatDialogRef<ManageOwnerAndGroupsDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
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
