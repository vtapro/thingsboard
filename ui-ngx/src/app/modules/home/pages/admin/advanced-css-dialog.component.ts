// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormControl } from '@angular/forms';

export interface AdvancedCssDialogData {
  advancedCss: string;
}

@Component({
    selector: 'tb-advanced-css-dialog',
    templateUrl: './advanced-css-dialog.component.html',
    standalone: false
})
export class AdvancedCssDialogComponent {

  advancedCssControl = new FormControl('');

  constructor(private dialogRef: MatDialogRef<AdvancedCssDialogComponent>,
              @Inject(MAT_DIALOG_DATA) private data: AdvancedCssDialogData) {
    this.advancedCssControl.setValue(this.data?.advancedCss || '');
  }

  cancel() {
    this.dialogRef.close();
  }

  save() {
    this.dialogRef.close(this.advancedCssControl.value);
  }

}
