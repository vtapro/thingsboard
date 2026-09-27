// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { PageComponent } from '@shared/components/page.component';
import { AppState } from '@core/core.state';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { entityDataToEntityInfo, EntityData, EntityKeyType } from '@shared/models/query/query.models';
import { PageData } from '@shared/models/page/page-data';

interface EntityGroup {
  id: string;
  name: string;
  entityType: string;
  entityIds: string[];
  allGroup?: boolean;
}

interface GroupEntity {
  id: { id: string };
  createdTime: number;
  name: string;
  label?: string;
  deviceProfileName?: string;
  assetProfileName?: string;
  type?: string;
}

/**
 * Entities of one entity group, like the "All: Devices" page of ThingsBoard PE.
 *
 * The "All" group is listed with the standard paginated API (server side paging), a regular group is loaded by the
 * entity ids stored in the group (see RbacEntityGroup.entityIds) and paginated on the client.
 */
@Component({
  selector: 'tb-entity-group-entities',
  templateUrl: './entity-group-entities.component.html',
  styleUrls: ['./entity-group-entities.component.scss'],
  standalone: false
})
export class EntityGroupEntitiesComponent extends PageComponent implements OnInit {

  entityType: string;
  group: EntityGroup;
  entities: GroupEntity[] = [];
  columns: string[] = [];
  loading = true;
  pageIndex = 0;
  pageSize = 10;
  totalElements = 0;

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private route: ActivatedRoute,
              private router: Router,
              private translate: TranslateService) {
    super(store);
    this.entityType = this.route.snapshot.data.entityType;
  }

  ngOnInit(): void {
    this.columns = this.entityType === 'ENTITY_VIEW'
      ? ['createdTime', 'name', 'type']
      : ['createdTime', 'name', 'profile', 'label'];
    const groupId = this.route.snapshot.params.groupId;
    this.http.get<{ groups: EntityGroup[] }>('/api/tenant/entityGroup',
      defaultHttpOptionsFromConfig(undefined)).subscribe(settings => {
      this.group = (settings?.groups || []).find(group => group.id === groupId
        && group.entityType === this.entityType);
      if (!this.group) {
        this.loading = false;
        return;
      }
      this.title = `${this.group.name}: ${this.translate.instant(this.titleKey())}`;
      this.loadPage();
    });
  }

  get title(): string {
    return this.pageTitle;
  }

  set title(value: string) {
    this.pageTitle = value;
  }

  private pageTitle = '';

  get entityNameLabel(): string {
    return this.translate.instant(this.nameKey());
  }

  get profileLabel(): string {
    return this.entityType === 'ASSET'
      ? this.translate.instant('asset-profile.asset-profile')
      : this.translate.instant('device-profile.device-profile');
  }

  nameOf(entity: GroupEntity): string {
    return entity.name;
  }

  profileOf(entity: GroupEntity): string {
    return entity.deviceProfileName || entity.assetProfileName || '-';
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadPage();
  }

  reload(): void {
    this.loading = true;
    this.loadPage();
  }

  back(): void {
    // go back to the GROUPS tab of the entity page
    this.router.navigate([this.listUrl()], {queryParams: {tab: 'groups'}});
  }

  addEntity(): void {
    this.router.navigateByUrl(this.listUrl());
  }

  private listUrl(): string {
    switch (this.entityType) {
      case 'ASSET':
        return '/entities/assets';
      case 'ENTITY_VIEW':
        return '/entities/entityViews';
      default:
        return '/entities/devices';
    }
  }

  private titleKey(): string {
    switch (this.entityType) {
      case 'ASSET':
        return 'asset.assets';
      case 'ENTITY_VIEW':
        return 'entity-view.entity-views';
      default:
        return 'device.devices';
    }
  }

  private nameKey(): string {
    switch (this.entityType) {
      case 'ASSET':
        return 'asset.name';
      case 'ENTITY_VIEW':
        return 'entity-view.name';
      default:
        return 'device.name';
    }
  }

  private listApi(): string {
    switch (this.entityType) {
      case 'ASSET':
        return '/api/tenant/assets';
      case 'ENTITY_VIEW':
        return '/api/tenant/entityViews';
      default:
        return '/api/tenant/devices';
    }
  }

  private loadPage(): void {
    if (!this.group) {
      return;
    }
    this.loading = true;
    if (this.group.allGroup) {
      // "All": every entity of the type, paginated by the backend
      const url = `${this.listApi()}?pageSize=${this.pageSize}&page=${this.pageIndex}` +
        `&sortProperty=createdTime&sortOrder=DESC`;
      this.http.get<{ data: GroupEntity[]; totalElements: number }>(url,
        defaultHttpOptionsFromConfig(undefined)).subscribe(page => {
        this.entities = page?.data || [];
        this.totalElements = page?.totalElements || 0;
        this.loading = false;
      }, () => {
        this.entities = [];
        this.totalElements = 0;
        this.loading = false;
      });
    } else {
      // a regular group: the ids are stored in the group
      const ids = this.group.entityIds || [];
      if (!ids.length) {
        this.entities = [];
        this.totalElements = 0;
        this.loading = false;
        return;
      }
      this.loadGroupPage(ids);
    }
  }

  /**
   * A regular group is listed with the entity query API and an "entity list" filter: the backend filters by the ids
   * of the group and pages/sorts the result, so a large group is handled the same way as the "All" group.
   */
  private loadGroupPage(ids: string[]): void {
    const query: any = {
      entityFilter: {
        type: 'entityList',
        entityType: this.entityType,
        entityList: ids
      },
      pageLink: {
        pageSize: this.pageSize,
        page: this.pageIndex,
        sortOrder: {
          key: {type: EntityKeyType.ENTITY_FIELD, key: 'createdTime'},
          direction: 'DESC'
        }
      },
      entityFields: [
        {type: EntityKeyType.ENTITY_FIELD, key: 'name'},
        {type: EntityKeyType.ENTITY_FIELD, key: 'createdTime'},
        {type: EntityKeyType.ENTITY_FIELD, key: 'label'}
      ],
      latestValues: [],
      keyFilters: []
    };
    this.http.post<PageData<EntityData>>('/api/entitiesQuery/find', query,
      defaultHttpOptionsFromConfig(undefined)).subscribe(page => {
      this.entities = (page?.data || []).map(entityData => {
        const info = entityDataToEntityInfo(entityData);
        const fields = (entityData.latest && entityData.latest[EntityKeyType.ENTITY_FIELD]) || {};
        return {
          id: info.id as any,
          createdTime: Number(fields.createdTime?.value) || 0,
          name: info.name,
          label: ((fields.label?.value as string) || info.label) as string
        };
      });
      this.totalElements = page?.totalElements || 0;
      this.loading = false;
    }, () => {
      this.entities = [];
      this.totalElements = 0;
      this.loading = false;
    });
  }

}
