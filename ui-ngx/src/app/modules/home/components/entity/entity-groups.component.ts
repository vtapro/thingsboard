// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';

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

  readonly displayedColumns = ['createdTime', 'name', 'description', 'publicGroup', 'members', 'actions'];

  groups: EntityGroup[] = [];

  nameControl = new FormControl('');
  descriptionControl = new FormControl('');
  publicControl = new FormControl(false);

  private allGroups: EntityGroup[] = [];

  constructor(protected store: Store<AppState>,
              private http: HttpClient) {
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

  addGroup() {
    const name = (this.nameControl.value || '').trim();
    if (!name) {
      return;
    }
    this.groups = [...this.groups, {
      id: Math.random().toString(36).substring(2, 10),
      name,
      entityType: this.entityType,
      entityIds: [],
      description: (this.descriptionControl.value || '').trim() || undefined,
      publicGroup: !!this.publicControl.value,
      createdTime: Date.now()
    }];
    this.nameControl.setValue('');
    this.descriptionControl.setValue('');
    this.publicControl.setValue(false);
  }

  removeGroup(group: EntityGroup) {
    this.groups = this.groups.filter(g => g.id !== group.id);
  }

  save() {
    const merged = [...this.allGroups.filter(group => group.entityType !== this.entityType), ...this.groups];
    this.http.post<{groups: EntityGroup[]}>('/api/tenant/entityGroup', {groups: merged},
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.allGroups = settings?.groups || [];
      this.groups = this.allGroups.filter(group => group.entityType === this.entityType);
    });
  }

}

