// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';

import { ActivatedRouteSnapshot, Router } from '@angular/router';

import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { Customer } from '@app/shared/models/customer.model';
import { CustomerService } from '@app/core/http/customer.service';
import { CustomerComponent } from '@modules/home/pages/customer/customer.component';
import { CustomerTabsComponent } from '@home/pages/customer/customer-tabs.component';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { HomeDialogsService } from '@home/dialogs/home-dialogs.service';
import { Authority } from '@shared/models/authority.enum';
import { hasRbacPermission } from '@core/services/rbac-permissions';

@Injectable()
export class CustomersTableConfigResolver  {

  private readonly config: EntityTableConfig<Customer> = new EntityTableConfig<Customer>();

  constructor(private customerService: CustomerService,
              private homeDialogs: HomeDialogsService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private router: Router,
              private store: Store<AppState>) {

    this.config.entityType = EntityType.CUSTOMER;
    this.config.entityComponent = CustomerComponent;
    this.config.entityTabsComponent = CustomerTabsComponent;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.CUSTOMER);
    this.config.entityResources = entityTypeResources.get(EntityType.CUSTOMER);
    const authState = getCurrentAuthState(this.store);

    this.config.columns.push(
      new DateEntityTableColumn<Customer>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<Customer>('title', 'customer.title', '25%'),
      new EntityTableColumn<Customer>('email', 'contact.email', '25%'),
      new EntityTableColumn<Customer>('country', 'contact.country', '25%'),
      new EntityTableColumn<Customer>('city', 'contact.city', '25%')
    );
    this.config.cellActionDescriptors.push(
      {
        name: this.translate.instant('customer.manage-customer-users'),
        icon: 'account_circle',
        isEnabled: (customer) => !customer.additionalInfo || !customer.additionalInfo.isPublic,
        onAction: ($event, entity) => this.manageCustomerUsers($event, entity)
      },
      {
        name: this.translate.instant('customer.manage-customers'),
        icon: 'mdi:account-multiple-outline',
        isEnabled: (customer) => true,
        onAction: ($event, entity) => this.manageSubCustomers($event, entity)
      },
      {
        name: this.translate.instant('customer.manage-customer-assets'),
        nameFunction: (customer) => {
          return customer.additionalInfo && customer.additionalInfo.isPublic
          ? this.translate.instant('customer.manage-public-assets')
          : this.translate.instant('customer.manage-customer-assets');
        },
        icon: 'domain',
        isEnabled: (customer) => true,
        onAction: ($event, entity) => this.manageCustomerAssets($event, entity)
      },
      {
        name: this.translate.instant('customer.manage-customer-devices'),
        nameFunction: (customer) => {
          return customer.additionalInfo && customer.additionalInfo.isPublic
            ? this.translate.instant('customer.manage-public-devices')
            : this.translate.instant('customer.manage-customer-devices');
        },
        icon: 'devices_other',
        isEnabled: (customer) => true,
        onAction: ($event, entity) => this.manageCustomerDevices($event, entity)
      },
      {
        name: this.translate.instant('customer.manage-customer-entity-views'),
        nameFunction: (customer) => {
          return customer.additionalInfo && customer.additionalInfo.isPublic
            ? this.translate.instant('customer.manage-public-entity-views')
            : this.translate.instant('customer.manage-customer-entity-views');
        },
        icon: 'mdi:view-quilt-outline',
        isEnabled: (customer) => true,
        onAction: ($event, entity) => this.manageCustomerEntityViews($event, entity)
      });
    if (authState.edgesSupportEnabled) {
      this.config.cellActionDescriptors.push(
        {
          name: this.translate.instant('customer.manage-customer-edges'),
          nameFunction: (customer) => {
            return customer.additionalInfo && customer.additionalInfo.isPublic
              ? this.translate.instant('customer.manage-public-edges')
              : this.translate.instant('customer.manage-customer-edges');
          },
          icon: 'router',
          isEnabled: (customer) => true,
          onAction: ($event, entity) => this.manageCustomerEdges($event, entity)
        }
      );
    }

    this.config.cellActionDescriptors.push(
      {
        name: this.translate.instant('customer.manage-customer-dashboards'),
        nameFunction: (customer) => {
          return customer.additionalInfo && customer.additionalInfo.isPublic
            ? this.translate.instant('customer.manage-public-dashboards')
            : this.translate.instant('customer.manage-customer-dashboards');
        },
        icon: 'dashboard',
        isEnabled: (customer) => true,
        onAction: ($event, entity) => this.manageCustomerDashboards($event, entity)
      }
    );

    this.config.deleteEntityTitle = customer => this.translate.instant('customer.delete-customer-title', { customerTitle: customer.title });
    this.config.deleteEntityContent = () => this.translate.instant('customer.delete-customer-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('customer.delete-customers-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('customer.delete-customers-text');

    this.config.entitiesFetchFunction = pageLink => this.customerService.getCustomers(pageLink);
    this.config.loadEntity = id => this.customerService.getCustomer(id.id);
    this.config.saveEntity = customer => this.customerService.saveCustomer(customer);
    this.config.deleteEntity = id => this.customerService.deleteCustomer(id.id);
    this.config.onEntityAction = action => this.onCustomerAction(action, this.config);
    this.config.deleteEnabled = (customer) => customer && (!customer.additionalInfo || !customer.additionalInfo.isPublic);
    this.config.entitySelectionEnabled = (customer) => customer && (!customer.additionalInfo || !customer.additionalInfo.isPublic);
    this.config.detailsReadonly = (customer) => customer && customer.additionalInfo && customer.additionalInfo.isPublic;
    const platformDeleteEnabled = this.config.deleteEnabled;
    this.config.deleteEnabled = (customer) => platformDeleteEnabled(customer)
      && hasRbacPermission('CUSTOMER', 'DELETE');
    if (authState.authUser?.authority === Authority.CUSTOMER_USER) {
      // a customer user sees the customers of its scope only; the links to the entities of a customer are tenant admin
      // features (there is a dedicated Users page for the members)
      this.config.cellActionDescriptors.splice(0);
      this.config.addEnabled = hasRbacPermission('CUSTOMER', 'CREATE');
    }
  }

  resolve(route: ActivatedRouteSnapshot): EntityTableConfig<Customer> {
    const customerId = route?.params?.customerId;
    if (customerId) {
      // the sub-customers page of one customer (the "Manage customers" action)
      this.config.tableTitle = this.translate.instant('customer.sub-customers');
      this.config.entitiesFetchFunction = pageLink => this.customerService.getSubCustomers(customerId, pageLink);
      this.config.addEnabled = true;
      // a customer created here becomes a child of the customer whose page is open
      this.config.saveEntity = customer => this.customerService.saveSubCustomer(customerId, customer);
    } else {
      this.config.tableTitle = this.translate.instant('customer.customers');
      this.config.saveEntity = customer => this.customerService.saveCustomer(customer);
    }

    return this.config;
  }

  manageSubCustomers($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/subCustomers`);
  }

  private openCustomer($event: Event, customer: Customer, config: EntityTableConfig<Customer>) {
    if ($event) {
      $event.stopPropagation();
    }
    const url = this.router.createUrlTree([customer.id.id], {relativeTo: config.getActivatedRoute()});
    this.router.navigateByUrl(url);
  }

  manageCustomerUsers($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/users`);
  }

  manageCustomerAssets($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/assets`);
  }

  manageCustomerEntityViews($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/entityViews`);
  }

  manageCustomerDevices($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/devices`);
  }

  manageCustomerDashboards($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/dashboards`);
  }

  manageCustomerEdges($event: Event, customer: Customer) {
    if ($event) {
      $event.stopPropagation();
    }
    this.router.navigateByUrl(`customers/${customer.id.id}/edgeInstances`);
  }

  onCustomerAction(action: EntityAction<Customer>, config: EntityTableConfig<Customer>): boolean {
    switch (action.action) {
      case 'open':
        this.openCustomer(action.event, action.entity, config);
        return true;
      case 'manageUsers':
        this.manageCustomerUsers(action.event, action.entity);
        return true;
      case 'manageCustomers':
        this.manageSubCustomers(action.event, action.entity);
        return true;
      case 'manageAssets':
        this.manageCustomerAssets(action.event, action.entity);
        return true;
      case 'manageEntityViews':
        this.manageCustomerEntityViews(action.event, action.entity);
        return true;
      case 'manageDevices':
        this.manageCustomerDevices(action.event, action.entity);
        return true;
      case 'manageDashboards':
        this.manageCustomerDashboards(action.event, action.entity);
        return true;
      case 'manageEdges':
        this.manageCustomerEdges(action.event, action.entity);
        return true;
    }
    return false;
  }

}
