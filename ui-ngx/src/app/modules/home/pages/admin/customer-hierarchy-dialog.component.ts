// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { FormControl, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

export interface CustomerHierarchyDialogCustomer {
  id: string;
  title: string;
}

export interface CustomerHierarchyRelation {
  childId: string;
  parentId: string;
}

export interface CustomerHierarchyDialogData {
  customers: CustomerHierarchyDialogCustomer[];
  /** Relations already declared, so a customer can not be given a second parent. */
  rows: CustomerHierarchyRelation[];
  /** Relation being edited (undefined when a new one is created). */
  row?: CustomerHierarchyRelation;
}

@Component({
    selector: 'tb-customer-hierarchy-dialog',
    templateUrl: './customer-hierarchy-dialog.component.html',
    standalone: false
})
export class CustomerHierarchyDialogComponent {

  childControl = new FormControl('', [Validators.required]);
  parentControl = new FormControl('', [Validators.required]);

  constructor(private dialogRef: MatDialogRef<CustomerHierarchyDialogComponent, CustomerHierarchyRelation>,
              @Inject(MAT_DIALOG_DATA) public data: CustomerHierarchyDialogData) {
    if (data?.row) {
      this.childControl.setValue(data.row.childId);
      this.parentControl.setValue(data.row.parentId);
    }
  }

  get isEdit(): boolean {
    return !!(this.data && this.data.row);
  }

  /** Customers that may be the child of a relation: the ones without parent (+ the one being edited). */
  get childOptions(): CustomerHierarchyDialogCustomer[] {
    const customers = this.data?.customers || [];
    const rows = this.data?.rows || [];
    const editedChild = this.data?.row?.childId;
    return customers.filter(customer =>
      customer.id === editedChild || !rows.some(row => row.childId === customer.id));
  }

  /** Customers that may be the parent: any customer but the selected child. */
  get parentOptions(): CustomerHierarchyDialogCustomer[] {
    const customers = this.data?.customers || [];
    return customers.filter(customer => customer.id !== this.childControl.value);
  }

  cancel(): void {
    this.dialogRef.close();
  }

  save(): void {
    if (this.childControl.invalid || this.parentControl.invalid) {
      this.childControl.markAsTouched();
      this.parentControl.markAsTouched();
      return;
    }
    this.dialogRef.close({childId: this.childControl.value, parentId: this.parentControl.value});
  }

}
