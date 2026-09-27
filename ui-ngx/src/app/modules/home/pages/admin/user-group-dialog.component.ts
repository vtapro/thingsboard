// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { FormControl, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

export interface UserGroupDialogData {
  /** Undefined when a new user group is created. */
  name?: string;
}

export interface UserGroupDialogResult {
  name: string;
}

@Component({
    selector: 'tb-user-group-dialog',
    templateUrl: './user-group-dialog.component.html',
    standalone: false
})
export class UserGroupDialogComponent {

  nameControl = new FormControl('', [Validators.required]);

  constructor(private dialogRef: MatDialogRef<UserGroupDialogComponent, UserGroupDialogResult>,
              @Inject(MAT_DIALOG_DATA) public data: UserGroupDialogData) {
    this.nameControl.setValue(data?.name || '');
  }

  get isEdit(): boolean {
    return !!(this.data && this.data.name);
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
    this.dialogRef.close({name});
  }

}
