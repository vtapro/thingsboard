// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { EntitiesTableComponent } from '../../components/entity/entities-table.component';
import { EntityGroupEntitiesComponent } from '../../components/entity/entity-group-entities.component';
import { EntityGroupResolver } from '../../components/entity/entity-group.resolver';
import { Authority } from '@shared/models/authority.enum';
import { DevicesTableConfigResolver } from '@modules/home/pages/device/devices-table-config.resolver';
import { EntityDetailsPageComponent } from '@home/components/entity/entity-details-page.component';
import { ConfirmOnExitGuard } from '@core/guards/confirm-on-exit.guard';
import { entityDetailsPageBreadcrumbLabelFunction } from '@home/pages/home-pages.models';
import { entityGroupBreadcrumbLabelFunction } from '@home/pages/home-pages.models';
import { BreadCrumbConfig } from '@shared/components/breadcrumb';
import { MenuId } from '@core/services/menu.models';

export const deviceRoutes: Routes = [
  {
    path: 'devices',
    data: {
      breadcrumb: {
        menuId: MenuId.devices
      }
    },
    children: [
      {
        path: '',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'device.devices',
          devicesType: 'tenant'
        },
        resolve: {
          entitiesTableConfig: DevicesTableConfigResolver
        }
      },
      {
        path: 'groups',
        data: {
          breadcrumb: {
            label: 'entity-group.groups',
            icon: 'layers'
          } as BreadCrumbConfig<any>
        },
        children: [
          {
            path: ':groupId',
            component: EntityGroupEntitiesComponent,
            data: {
              auth: [Authority.TENANT_ADMIN],
              entityType: 'DEVICE',
              devicesType: 'tenant',
              breadcrumb: {
                labelFunction: entityGroupBreadcrumbLabelFunction,
                icon: 'layers'
              } as BreadCrumbConfig<any>
            },
            resolve: {
              entitiesTableConfig: DevicesTableConfigResolver,
              entityGroup: EntityGroupResolver
            }
          }
        ]
      },
      {
        path: ':entityId',
        component: EntityDetailsPageComponent,
        canDeactivate: [ConfirmOnExitGuard],
        data: {
          breadcrumb: {
            labelFunction: entityDetailsPageBreadcrumbLabelFunction,
            icon: 'devices_other'
          } as BreadCrumbConfig<EntityDetailsPageComponent>,
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'device.devices',
          devicesType: 'tenant'
        },
        resolve: {
          entitiesTableConfig: DevicesTableConfigResolver
        }
      }
    ]
  }
];

const routes: Routes = [
  {
    path: 'devices',
    pathMatch: 'full',
    redirectTo: '/entities/devices'
  },
  {
    path: 'devices/:entityId',
    redirectTo: '/entities/devices/:entityId'
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
  providers: [
    DevicesTableConfigResolver
  ]
})
export class DeviceRoutingModule { }
