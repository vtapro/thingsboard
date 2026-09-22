// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { CustomMenuService } from '@core/http/custom-menu.service';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { PageComponent } from '@shared/components/page.component';
import { customMenuAssigneeTypes, CustomMenuItem } from '@shared/models/custom-menu.models';

@Component({
    selector: 'tb-custom-menu',
    templateUrl: './custom-menu.component.html',
    styleUrls: ['./custom-menu.component.scss', './settings-card.scss'],
    standalone: false
})
export class CustomMenuComponent extends PageComponent implements OnInit {

  readonly assigneeTypes = customMenuAssigneeTypes;
  readonly displayedColumns = ['order', 'name', 'type', 'target', 'assigneeType', 'actions'];

  items: CustomMenuItem[] = [];

  nameControl = new FormControl('');
  typeControl = new FormControl('dashboard');
  targetControl = new FormControl('');
  assigneeControl = new FormControl('');
  iconControl = new FormControl('');

  constructor(protected store: Store<AppState>,
              private customMenuService: CustomMenuService) {
    super();
  }

  ngOnInit() {
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.load();
    }
  }

  load() {
    this.customMenuService.getCustomMenuSettings().subscribe(settings => {
      this.items = settings?.items || [];
    });
  }

  addItem() {
    const name = (this.nameControl.value || '').trim();
    const target = (this.targetControl.value || '').trim();
    if (!name || !target) {
      return;
    }
    this.items = [...this.items, {
      id: this.generateId(),
      name,
      icon: (this.iconControl.value || '').trim() || 'mdi:link-variant',
      type: this.typeControl.value as 'dashboard' | 'url',
      target,
      assigneeType: this.assigneeControl.value,
      order: this.items.length + 1
    }];
    this.nameControl.setValue('');
    this.targetControl.setValue('');
    this.iconControl.setValue('');
  }

  removeItem(item: CustomMenuItem) {
    this.items = this.items.filter(i => i.id !== item.id).map((i, index) => ({...i, order: index + 1}));
  }

  moveUp(index: number) {
    if (index > 0) {
      const items = [...this.items];
      [items[index - 1], items[index]] = [items[index], items[index - 1]];
      this.items = items.map((i, idx) => ({...i, order: idx + 1}));
    }
  }

  save() {
    this.customMenuService.saveCustomMenuSettings({items: this.items}).subscribe(saved => {
      this.items = saved?.items || [];
    });
  }

  private generateId(): string {
    return Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
  }

}

