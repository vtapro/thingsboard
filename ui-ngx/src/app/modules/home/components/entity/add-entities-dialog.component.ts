// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { SelectionModel } from '@angular/cdk/collections';

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
  selection = new SelectionModel<string>(true, []);
  loading = true;

  constructor(private dialogRef: MatDialogRef<AddEntitiesDialogComponent>,
              @Inject(MAT_DIALOG_DATA) private data: AddEntitiesDialogData,
              private http: HttpClient) {
  }

  ngOnInit() {
    const path = this.data.entityType === 'DEVICE' ? 'devices'
      : this.data.entityType === 'ASSET' ? 'assets' : 'entityViews';
    const selected = this.data.selectedIds || [];
    this.http.get<{data: Array<{id: {id: string}; name: string}>}>(`/api/tenant/${path}?pageSize=100&page=0`,
      defaultHttpOptionsFromConfig(undefined)).subscribe(page => {
      this.entities = (page?.data || []).map(entity => ({id: entity.id.id, name: entity.name}));
      this.selection = new SelectionModel<string>(true, this.entities
        .filter(entity => selected.includes(entity.id))
        .map(entity => entity.id));
      this.loading = false;
    });
  }

  cancel() {
    this.dialogRef.close();
  }

  save() {
    this.dialogRef.close(this.selection.selected);
  }

}

