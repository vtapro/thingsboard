// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { PageComponent } from '@shared/components/page.component';
import { AppState } from '@core/core.state';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { DialogService } from '@core/services/dialog.service';
import { entityDataToEntityInfo, EntityData, EntityKeyType } from '@shared/models/query/query.models';
import { PageData } from '@shared/models/page/page-data';
import { BaseData, HasId } from '@shared/models/base-data';
import { EntityId } from '@shared/models/id/entity-id';
import { EntityType } from '@shared/models/entity-type.models';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatDialog } from '@angular/material/dialog';
import { AddEntityDialogComponent } from './add-entity-dialog.component';
import { EntityDetailsPanelComponent } from './entity-details-panel.component';
import { EntityAction } from '@home/models/entity/entity-component.models';

import { EntityGroupInfo } from './entity-group.resolver';

interface GroupEntity {
  id: string;
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
  group: EntityGroupInfo;
  entities: GroupEntity[] = [];
  columns: string[] = [];
  loading = true;
  pageIndex = 0;
  pageSize = 10;
  totalElements = 0;
  /** Entity whose details are shown in the side panel (like the "All" table of the entity type). */
  isDetailsOpen = false;
  selectedEntityId: EntityId = null;

  @ViewChild('entityDetailsPanel') entityDetailsPanel: EntityDetailsPanelComponent;

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private route: ActivatedRoute,
              private router: Router,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService) {
    super(store);
    this.entityType = this.route.snapshot.data.entityType;
  }

  ngOnInit(): void {
    this.columns = this.entityType === 'ENTITY_VIEW'
      ? ['createdTime', 'name', 'type']
      : ['createdTime', 'name', 'profile', 'label'];
    // the group and the config of the entity type are resolved by the router
    this.group = this.route.snapshot.data.entityGroup;
    this.tableConfig = this.route.snapshot.data.entitiesTableConfig;
    if (!this.group) {
      this.loading = false;
      return;
    }
    this.title = `${this.group.name}: ${this.translate.instant(this.titleKey())}`;
    this.loadPage();
  }

  get title(): string {
    return this.pageTitle;
  }

  set title(value: string) {
    this.pageTitle = value;
  }

  private pageTitle = '';
  /** Entity table config of the entity type (resolved by the router), needed by the details panel. */
  tableConfig: any;

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

  /** Clicking a row opens the entity details panel (PE opens the same panel from the entities of a group). */
  onRowClick(entity: GroupEntity): void {
    const entityId: EntityId = {id: entity.id, entityType: this.entityType as EntityType};
    if (this.selectedEntityId?.id === entityId.id) {
      this.isDetailsOpen = !this.isDetailsOpen;
      return;
    }
    this.selectedEntityId = entityId;
    this.isDetailsOpen = true;
  }

  closeEntityDetails(): void {
    this.isDetailsOpen = false;
  }

  /** The entity was changed (renamed, profile changed, ...) from the details panel: refresh the list. */
  onEntityUpdated(): void {
    this.reload();
  }

  /** The details panel asks the page to delete the entity. */
  onEntityAction(action: EntityAction<BaseData<HasId>>): void {
    if (action.action !== 'delete' || !this.tableConfig) {
      return;
    }
    this.dialogService.confirm(
      this.tableConfig.deleteEntityTitle(action.entity),
      this.tableConfig.deleteEntityContent(action.entity),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe(result => {
      if (result) {
        this.tableConfig.deleteEntity(action.entity.id).subscribe(() => {
          this.isDetailsOpen = false;
          this.selectedEntityId = null;
          this.reload();
        });
      }
    });
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
    if (!this.group || !this.tableConfig) {
      this.router.navigateByUrl(this.listUrl());
      return;
    }
    // like ThingsBoard PE: create the entity and add it to the group
    this.dialog.open(AddEntityDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {entitiesTableConfig: this.tableConfig}
    }).afterClosed().subscribe((created: any) => {
      if (created?.id?.id) {
        const updated: EntityGroupInfo = {...this.group, entityIds: [...(this.group.entityIds || []), created.id.id]};
        this.http.post('/api/tenant/entityGroup/group', updated,
          defaultHttpOptionsFromConfig({ignoreErrors: true})).subscribe(() => {
          this.group = updated;
          this.pageIndex = 0;
          this.reload();
        }, () => this.reload());
      }
    });
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
        this.clampPageIndex();
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
          id: entityData.entityId.id,
          createdTime: Number(fields.createdTime?.value) || 0,
          name: info.name,
          label: ((fields.label?.value as string) || info.label) as string
        };
      });
      this.enrichProfiles(this.entities);
      this.totalElements = page?.totalElements || 0;
      this.loading = false;
      this.clampPageIndex();
    }, () => {
      this.entities = [];
      this.totalElements = 0;
      this.loading = false;
    });
  }

  /**
   * MatPaginator does not clamp the page index when the number of elements shrinks: after deleting the last entity of
   * the last page the user would stay on an empty page without a way back.
   */
  private clampPageIndex(): void {
    const lastPage = Math.max(0, Math.ceil(this.totalElements / this.pageSize) - 1);
    if (this.pageIndex > lastPage) {
      this.pageIndex = lastPage;
      this.loadPage();
    }
  }

  /**
   * The entity query API does not return the profile of a device/asset, so the profile names of the current page are
   * resolved with two small batches of calls (entities then their profiles).
   */
  private enrichProfiles(entities: GroupEntity[]): void {
    if (this.entityType === 'ENTITY_VIEW' || !entities.length) {
      return;
    }
    const entityApi = this.entityType === 'ASSET' ? '/api/asset/' : '/api/device/';
    const profileApi = this.entityType === 'ASSET' ? '/api/assetProfileInfo/' : '/api/deviceProfileInfo/';
    forkJoin(entities.map(entity => this.http.get<any>(entityApi + entity.id,
      defaultHttpOptionsFromConfig(undefined)).pipe(catchError(() => of(null))))).subscribe(details => {
      const profileIds: string[] = [];
      details.forEach(detail => {
        const profileId = detail?.deviceProfileId?.id || detail?.assetProfileId?.id;
        if (profileId && !profileIds.includes(profileId)) {
          profileIds.push(profileId);
        }
      });
      if (!profileIds.length) {
        return;
      }
      forkJoin(profileIds.map(profileId => this.http.get<any>(profileApi + profileId,
        defaultHttpOptionsFromConfig(undefined)).pipe(catchError(() => of(null))))).subscribe(profiles => {
        const names = new Map<string, string>();
        profiles.filter(profile => !!profile).forEach(profile => names.set(profile.id.id, profile.name));
        entities.forEach((entity, index) => {
          const profileId = details[index]?.deviceProfileId?.id || details[index]?.assetProfileId?.id;
          const name = profileId ? names.get(profileId) : null;
          if (name) {
            if (this.entityType === 'ASSET') {
              entity.assetProfileName = name;
            } else {
              entity.deviceProfileName = name;
            }
          }
        });
      });
    });
  }

}
