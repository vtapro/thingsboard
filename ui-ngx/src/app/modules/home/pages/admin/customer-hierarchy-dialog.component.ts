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

  /** True when the selected relation would create a loop in the hierarchy. */
  cycleError = false;

  constructor(private dialogRef: MatDialogRef<CustomerHierarchyDialogComponent, CustomerHierarchyRelation>,
              @Inject(MAT_DIALOG_DATA) public data: CustomerHierarchyDialogData) {
    if (data?.row) {
      this.childControl.setValue(data.row.childId);
      this.parentControl.setValue(data.row.parentId);
    }
    this.childControl.valueChanges.subscribe(() => this.refreshCycleError());
    this.parentControl.valueChanges.subscribe(() => this.refreshCycleError());
    this.refreshCycleError();
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

  /** Customers that may be the parent: any customer but the selected child and its descendants. */
  get parentOptions(): CustomerHierarchyDialogCustomer[] {
    const customers = this.data?.customers || [];
    const childId = this.childControl.value;
    return customers.filter(customer => !this.wouldCreateCycle(childId, customer.id));
  }

  /**
   * True when the parent is the child itself or one of its descendants, which would create a loop in the hierarchy
   * (a customer can not be its own ancestor).
   */
  private wouldCreateCycle(childId: string, parentId: string): boolean {
    if (!childId || !parentId) {
      return false;
    }
    const parents = new Map<string, string>();
    (this.data?.rows || [])
      .filter(row => row.childId !== this.data?.row?.childId)
      .forEach(row => parents.set(row.childId, row.parentId));
    const visited = new Set<string>();
    let current: string = parentId;
    while (current && !visited.has(current)) {
      if (current === childId) {
        return true;
      }
      visited.add(current);
      current = parents.get(current);
    }
    return false;
  }

  private refreshCycleError(): void {
    this.cycleError = this.wouldCreateCycle(this.childControl.value, this.parentControl.value);
  }

  cancel(): void {
    this.dialogRef.close();
  }

  save(): void {
    this.refreshCycleError();
    if (this.childControl.invalid || this.parentControl.invalid || this.cycleError) {
      this.childControl.markAsTouched();
      this.parentControl.markAsTouched();
      return;
    }
    this.dialogRef.close({childId: this.childControl.value, parentId: this.parentControl.value});
  }

}
