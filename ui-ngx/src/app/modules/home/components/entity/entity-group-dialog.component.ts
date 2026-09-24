// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

export interface EntityGroupDialogData {
  name?: string;
  description?: string;
  publicGroup?: boolean;
  entityType?: string;
  /** When provided, the dialog lets the administrator choose the entity type of a new group. */
  entityTypes?: string[];
}

@Component({
    selector: 'tb-entity-group-dialog',
    templateUrl: './entity-group-dialog.component.html',
    standalone: false
})
export class EntityGroupDialogComponent {

  groupForm: FormGroup;

  constructor(private dialogRef: MatDialogRef<EntityGroupDialogComponent>,
              @Inject(MAT_DIALOG_DATA) private data: EntityGroupDialogData,
              private fb: FormBuilder) {
    this.groupForm = this.fb.group({
      name: [null, [Validators.required]],
      description: [null],
      publicGroup: [false],
      entityType: [data?.entityType || data?.entityTypes?.[0] || 'DEVICE']
    });
    if (data) {
      this.groupForm.patchValue({
        name: data.name,
        description: data.description,
        publicGroup: !!data.publicGroup,
        entityType: data.entityType || data.entityTypes?.[0] || 'DEVICE'
      });
    }
  }

  get entityTypes(): string[] {
    return this.data?.entityTypes || [];
  }

  get isEdit(): boolean {
    return !!(this.data && this.data.name);
  }

  cancel() {
    this.dialogRef.close();
  }

  save() {
    if (this.groupForm.invalid) {
      this.groupForm.markAllAsTouched();
      return;
    }
    this.dialogRef.close(this.groupForm.value);
  }

}
