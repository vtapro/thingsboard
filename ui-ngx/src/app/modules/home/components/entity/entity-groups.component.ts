// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';
import { MatDialog } from '@angular/material/dialog';
import { AddEntitiesDialogComponent } from '@home/components/entity/add-entities-dialog.component';
import { EntityGroupDialogComponent } from '@home/components/entity/entity-group-dialog.component';

import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';
import { SelectionModel } from '@angular/cdk/collections';

interface EntityGroup {
  id: string;
  name: string;
  entityType: string;
  entityIds: string[];
  description?: string;
  publicGroup?: boolean;
  createdTime?: number;
}

@Component({
    selector: 'tb-entity-groups',
    templateUrl: './entity-groups.component.html',
    styleUrls: ['./entity-groups.component.scss'],
    standalone: false
})
export class EntityGroupsComponent extends PageComponent implements OnInit {

  @Input() entityType = 'DEVICE';

  readonly displayedColumns = ['select', 'createdTime', 'name', 'description', 'publicGroup', 'members', 'actions'];

  groups: EntityGroup[] = [];
  selection = new SelectionModel<EntityGroup>(true, []);

  private allGroups: EntityGroup[] = [];

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private dialog: MatDialog) {
    super();
  }

  ngOnInit() {
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.load();
    }
  }

  isTenantAdmin(): boolean {
    return getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN;
  }

  load() {
    this.http.get<{groups: EntityGroup[]}>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.allGroups = settings?.groups || [];
      this.groups = this.allGroups.filter(group => group.entityType === this.entityType);
    });
  }

  groupTitle(): string {
    switch (this.entityType) {
      case 'ASSET':
        return 'Asset groups';
      case 'ENTITY_VIEW':
        return 'Entity view groups';
      default:
        return 'Device groups';
    }
  }

  isAllSelected(): boolean {
    return this.groups.length > 0 && this.selection.selected.length === this.groups.length;
  }

  toggleAll() {
    if (this.isAllSelected()) {
      this.selection.clear();
    } else {
      this.groups.forEach(group => this.selection.select(group));
    }
  }

  openGroupDialog(group?: EntityGroup) {
    this.dialog.open(EntityGroupDialogComponent, {
      data: group ? {
        name: group.name,
        description: group.description,
        publicGroup: group.publicGroup
      } : {},
      width: '480px'
    }).afterClosed().subscribe((value: {name: string; description: string; publicGroup: boolean}) => {
      if (!value) {
        return;
      }
      if (group) {
        this.groups = this.groups.map(g => g.id === group.id ? {...g, ...value} : g);
      } else {
        this.groups = [...this.groups, {
          id: Math.random().toString(36).substring(2, 10),
          name: value.name,
          entityType: this.entityType,
          entityIds: [],
          description: value.description || undefined,
          publicGroup: !!value.publicGroup,
          createdTime: Date.now()
        }];
      }
      this.persist();
    });
  }

  removeGroup(group: EntityGroup) {
    this.groups = this.groups.filter(g => g.id !== group.id);
    this.persist();
  }

  togglePublic(group: EntityGroup) {
    this.groups = this.groups.map(g => g.id === group.id ? {...g, publicGroup: !g.publicGroup} : g);
    this.persist();
  }

  addMembers(group: EntityGroup) {
    this.dialog.open(AddEntitiesDialogComponent, {
      data: {entityType: this.entityType, selectedIds: group.entityIds || []},
      width: '480px'
    }).afterClosed().subscribe((entityIds: string[]) => {
      if (entityIds) {
        this.groups = this.groups.map(g => g.id === group.id ? {...g, entityIds} : g);
        this.persist();
      }
    });
  }

  save() {
    this.persist();
  }

  private persist() {
    const merged = [...this.allGroups.filter(group => group.entityType !== this.entityType), ...this.groups];
    this.http.post<{groups: EntityGroup[]}>('/api/tenant/entityGroup', {groups: merged},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.allGroups = settings?.groups || [];
      this.groups = this.allGroups.filter(group => group.entityType === this.entityType);
    });
  }

}
