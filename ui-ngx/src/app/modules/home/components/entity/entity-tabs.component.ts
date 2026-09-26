// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { BaseData, HasId } from '@shared/models/base-data';
import { PageComponent } from '@shared/components/page.component';
import { AfterViewInit, Directive, inject, Input, OnInit, QueryList, ViewChildren } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { MatTab } from '@angular/material/tabs';
import { BehaviorSubject } from 'rxjs';
import { Authority } from '@app/shared/models/authority.enum';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { AuthUser } from '@shared/models/user.model';
import { EntityType } from '@shared/models/entity-type.models';
import { AuditLogMode } from '@shared/models/audit-log.models';
import { DebugEventType, EventType } from '@shared/models/event.models';
import { AttributeScope, LatestTelemetry } from '@shared/models/telemetry/telemetry.models';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { UntypedFormGroup } from '@angular/forms';
import { PageLink } from '@shared/models/page/page-link';
import { hasRbacPermission } from '@core/services/rbac-permissions';
import { EntityId } from '@shared/models/id/entity-id';

@Directive()
// eslint-disable-next-line @angular-eslint/directive-class-suffix
export abstract class EntityTabsComponent<T extends BaseData<HasId>,
  P extends PageLink = PageLink,
  L extends BaseData<HasId> = T,
  C extends EntityTableConfig<T, P, L> = EntityTableConfig<T, P, L>>
  extends PageComponent implements OnInit, AfterViewInit {

  attributeScopes = AttributeScope;
  latestTelemetryTypes = LatestTelemetry;

  authorities = Authority;

  entityTypes = EntityType;

  auditLogModes = AuditLogMode;

  eventTypes = EventType;

  debugEventTypes = DebugEventType;

  authUser: AuthUser;

  nullUid = NULL_UUID;

  entityValue: T;

  entitiesTableConfigValue: C;

  @ViewChildren(MatTab) entityTabs: QueryList<MatTab>;

  isEditValue: boolean;

  @Input()
  set isEdit(isEdit: boolean) {
    this.isEditValue = isEdit;
  }

  get isEdit() {
    return this.isEditValue;
  }

  @Input()
  set entity(entity: T) {
    this.setEntity(entity);
  }

  get entity(): T {
    return this.entityValue;
  }

  /**
   * A detailed custom role may deny the attributes/telemetry of an entity type: the tab is not offered then,
   * otherwise the WEB UI would call an API that answers 403.
   */
  get canReadAttributes(): boolean {
    return hasRbacPermission(this.entityResource(), 'READ_ATTRIBUTES');
  }

  get canReadTelemetry(): boolean {
    return hasRbacPermission(this.entityResource(), 'READ_TELEMETRY');
  }

  private entityResource(): string {
    const entityId = this.entity?.id as EntityId;
    return entityId ? entityId.entityType : null;
  }

  @Input()
  set entitiesTableConfig(entitiesTableConfig: C) {
    this.setEntitiesTableConfig(entitiesTableConfig);
  }

  get entitiesTableConfig(): C {
    return this.entitiesTableConfigValue;
  }

  @Input()
  detailsForm: UntypedFormGroup;

  private entityTabsSubject = new BehaviorSubject<Array<MatTab>>(null);

  entityTabsChanged = this.entityTabsSubject.asObservable();

  protected store: Store<AppState> = inject(Store<AppState>);

  protected constructor(...args: unknown[]) {
    super();
    this.authUser = getCurrentAuthUser(this.store);
  }

  ngOnInit() {
  }

  ngAfterViewInit(): void {
    this.entityTabsSubject.next(this.entityTabs.toArray());
    this.entityTabs.changes.subscribe(
      () => {
        this.entityTabsSubject.next(this.entityTabs.toArray());
      }
    );
  }

  resolveTabIndex(tab: string): number {
    return 0;
  }

  protected setEntity(entity: T) {
    this.entityValue = entity;
  }

  protected setEntitiesTableConfig(entitiesTableConfig: C) {
    this.entitiesTableConfigValue = entitiesTableConfig;
  }

}
