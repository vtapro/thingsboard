// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';

export interface AddEntitiesDialogData {
  entityType: string;
  selectedIds: string[];
}

@Component({
    selector: 'tb-add-entities-dialog',
    templateUrl: './add-entities-dialog.component.html',
    standalone: false
})
export class AddEntitiesDialogComponent implements OnInit {

  entities: Array<{id: string; name: string}> = [];
  /**
   * Ids of the selected entities. A plain array is used on purpose: mixing the [selected] binding of
   * mat-selection-list with ngModel made the check boxes and the value returned by the dialog disagree.
   */
  selectedIds: string[] = [];
  loading = true;
  /** The picker is not searchable, so every page of the entity type is loaded (up to MAX_PAGES pages). */
  truncated = false;

  private static readonly PAGE_SIZE = 100;
  private static readonly MAX_PAGES = 50;

  constructor(private dialogRef: MatDialogRef<AddEntitiesDialogComponent>,
              @Inject(MAT_DIALOG_DATA) private data: AddEntitiesDialogData,
              private http: HttpClient) {
  }

  ngOnInit() {
    const path = this.data.entityType === 'DEVICE' ? 'devices'
      : this.data.entityType === 'ASSET' ? 'assets' : 'entityViews';
    this.selectedIds = [...(this.data.selectedIds || [])];
    this.loadEntities(path, 0, []);
  }

  /** Loads the entities of the type page by page: the first page alone would hide the entities of a large tenant. */
  private loadEntities(path: string, page: number, entities: Array<{id: string; name: string}>): void {
    this.http.get<{data: Array<{id: {id: string}; name: string}>; hasNext: boolean}>(
      `/api/tenant/${path}?pageSize=${AddEntitiesDialogComponent.PAGE_SIZE}&page=${page}`,
      defaultHttpOptionsFromConfig(undefined)).subscribe({
      next: pageData => {
        const loaded = entities.concat((pageData?.data || [])
          .map(entity => ({id: entity.id.id, name: entity.name})));
        if (pageData?.hasNext && page + 1 < AddEntitiesDialogComponent.MAX_PAGES) {
          this.loadEntities(path, page + 1, loaded);
        } else {
          this.truncated = !!pageData?.hasNext;
          this.entities = loaded;
          this.loading = false;
        }
      },
      error: () => {
        this.entities = entities;
        this.loading = false;
      }
    });
  }

  isSelected(entityId: string): boolean {
    return this.selectedIds.includes(entityId);
  }

  toggle(entityId: string, checked: boolean) {
    if (checked) {
      if (!this.selectedIds.includes(entityId)) {
        this.selectedIds = [...this.selectedIds, entityId];
      }
    } else {
      this.selectedIds = this.selectedIds.filter(id => id !== entityId);
    }
  }

  cancel() {
    this.dialogRef.close();
  }

  save() {
    this.dialogRef.close(this.selectedIds);
  }

}
