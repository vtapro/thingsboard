// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { EntitiesTableComponent } from '../../components/entity/entities-table.component';
import { EntityGroupEntitiesComponent } from '../../components/entity/entity-group-entities.component';
import { EntityGroupResolver } from '../../components/entity/entity-group.resolver';
import { UsersTableConfigResolver } from '@modules/home/pages/user/users-table-config.resolver';
import { Authority } from '@shared/models/authority.enum';
import { EntityDetailsPageComponent } from '@home/components/entity/entity-details-page.component';
import { ConfirmOnExitGuard } from '@core/guards/confirm-on-exit.guard';
import { entityDetailsPageBreadcrumbLabelFunction } from '@home/pages/home-pages.models';
import { entityGroupBreadcrumbLabelFunction } from '@home/pages/home-pages.models';
import { BreadCrumbConfig } from '@shared/components/breadcrumb';
import { MenuId } from '@core/services/menu.models';

const routes: Routes = [
  {
    path: 'users',
    data: {
      breadcrumb: {
        menuId: MenuId.users
      }
    },
    children: [
      {
        path: '',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'user.users'
        },
        resolve: {
          entitiesTableConfig: UsersTableConfigResolver
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
              entityType: 'USER',
              breadcrumb: {
                labelFunction: entityGroupBreadcrumbLabelFunction,
                icon: 'layers'
              } as BreadCrumbConfig<any>
            },
            resolve: {
              entitiesTableConfig: UsersTableConfigResolver,
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
            icon: 'account_circle'
          } as BreadCrumbConfig<EntityDetailsPageComponent>,
          auth: [Authority.SYS_ADMIN, Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'user.user',
        },
        resolve: {
          entitiesTableConfig: UsersTableConfigResolver
        }
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
  providers: [
    UsersTableConfigResolver
  ]
})
export class UserRoutingModule { }
