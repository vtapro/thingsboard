// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

@Component({
    selector: 'tb-entity-groups-page',
    templateUrl: './entity-groups-page.component.html',
    styleUrls: ['./entity-groups-page.component.scss'],
    standalone: false
})
export class EntityGroupsPageComponent implements OnInit {

  entityType: string;

  @Input()
  entitiesTableConfig: any;

  constructor(private route: ActivatedRoute) {
  }

  ngOnInit() {
    this.entityType = this.route.snapshot.data.entityType;
    if (!this.entitiesTableConfig) {
      this.entitiesTableConfig = this.route.snapshot.data.entitiesTableConfig;
    }
    if (!this.entitiesTableConfig) {
      this.entitiesTableConfig = this.findEntitiesTableConfig(this.route.root);
    }
  }

  private findEntitiesTableConfig(route: ActivatedRoute): any {
    let current: ActivatedRoute = route;
    while (current) {
      const config = current.snapshot?.data?.entitiesTableConfig;
      if (config) {
        return config;
      }
      current = current.firstChild;
    }
    return undefined;
  }

  allIcon(): string {
    switch (this.entityType) {
      case 'ASSET':
        return 'category';
      case 'ENTITY_VIEW':
        return 'visibility';
      default:
        return 'devices_other';
    }
  }

}
