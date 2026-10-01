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
import { User } from '@shared/models/user.model';
import { UserService } from '@core/http/user.service';
import { UserComponent } from '@modules/home/pages/user/user.component';
import { CustomerService } from '@core/http/customer.service';
import { map, mergeMap, take, tap } from 'rxjs/operators';
import { Observable, of } from 'rxjs';
import { Authority } from '@shared/models/authority.enum';
import { CustomerId } from '@shared/models/id/customer-id';
import { MatDialog } from '@angular/material/dialog';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { AddUserDialogComponent, AddUserDialogData } from '@modules/home/pages/user/add-user-dialog.component';
import { AuthState } from '@core/auth/auth.models';
import { select, Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { selectAuth } from '@core/auth/auth.selectors';
import { AuthService } from '@core/auth/auth.service';
import {
  ActivationLinkDialogComponent,
  ActivationLinkDialogData
} from '@modules/home/pages/user/activation-link-dialog.component';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { TenantService } from '@app/core/http/tenant.service';
import { TenantId } from '@app/shared/models/id/tenant-id';
import { UserTabsComponent } from '@home/pages/user/user-tabs.component';
import { isDefinedAndNotNull } from '@core/utils';
import { hasExplicitRbacPermission, hasRbacPermission } from '@core/services/rbac-permissions';
import {
  ManageOwnerAndGroupsDialogComponent,
  ManageOwnerAndGroupsDialogData
} from '@home/dialogs/manage-owner-and-groups-dialog.component';

export interface UsersTableRouteData {
  authority: Authority;
}

@Injectable()
export class UsersTableConfigResolver  {

  private readonly config: EntityTableConfig<User> = new EntityTableConfig<User>();

  private tenantId: string;
  private customerId: string;
  private authority: Authority;
  private authUser: User;
  /** Tenant administrator viewing /users: a read-only list of every user of the tenant. */
  private allUsers = false;

  constructor(private store: Store<AppState>,
              private userService: UserService,
              private authService: AuthService,
              private tenantService: TenantService,
              private customerService: CustomerService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private router: Router,
              private dialog: MatDialog) {

    this.config.entityType = EntityType.USER;
    this.config.entityComponent = UserComponent;
    this.config.entityTabsComponent = UserTabsComponent;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.USER);
    this.config.entityResources = entityTypeResources.get(EntityType.USER);

    this.config.columns.push(
      new DateEntityTableColumn<User>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<User>('firstName', 'user.first-name', '33%'),
      new EntityTableColumn<User>('lastName', 'user.last-name', '33%'),
      new EntityTableColumn<User>('email', 'user.email', '33%')
    );

    this.config.deleteEnabled = user => user && user.id && user.id.id !== this.authUser.id.id
      && hasRbacPermission('USER', 'DELETE');
    this.config.deleteEntityTitle = user => this.translate.instant('user.delete-user-title', { userEmail: user.email });
    this.config.deleteEntityContent = () => this.translate.instant('user.delete-user-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('user.delete-users-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('user.delete-users-text');

    this.config.loadEntity = id => this.userService.getUser(id.id);
    this.config.saveEntity = user => this.saveUser(user);
    this.config.deleteEntity = id => this.userService.deleteUser(id.id);
    this.config.onEntityAction = action => this.onUserAction(action, this.config);
    this.config.addEntity = () => this.addUser();
  }

  resolve(route: ActivatedRouteSnapshot): Observable<EntityTableConfig<User>> {
    const routeParams = route.params;
    return this.store.pipe(select(selectAuth), take(1)).pipe(
      tap((auth) => {
        this.authUser = auth.userDetails;
        this.allUsers = false;
        if (routeParams.tenantId) {
          this.authority = Authority.TENANT_ADMIN;
          this.tenantId = routeParams.tenantId;
          this.customerId = NULL_UUID;
          this.config.entitiesFetchFunction = pageLink => this.userService.getTenantAdmins(this.tenantId, pageLink);
        } else if (routeParams.customerId) {
          this.authority = Authority.CUSTOMER_USER;
          this.tenantId = this.authUser.tenantId.id;
          this.customerId = routeParams.customerId;
          this.config.entitiesFetchFunction = pageLink => this.userService.getCustomerUsers(this.customerId, pageLink);
          // a customer user manages the members of its own customer only, and only when its role grants it
          this.config.addEnabled = hasRbacPermission('USER', 'CREATE');
        } else if (this.authUser.authority === Authority.CUSTOMER_USER) {
          this.authority = Authority.CUSTOMER_USER;
          this.tenantId = this.authUser.tenantId.id;
          this.customerId = this.authUser.customerId?.id;
          // GET /api/users is available to a customer user and returns the users of its customer, filtered by the role
          this.config.entitiesFetchFunction = pageLink => this.userService.getUsers(pageLink);
          // the platform denies creating a user to a customer user, so the role has to grant it explicitly
          this.config.addEnabled = hasExplicitRbacPermission('USER', 'CREATE');
        } else {
          // tenant administrator: every user of the tenant; adding here creates a tenant level user
          // (authority TENANT_ADMIN, no customer). Role limited members are created under a customer.
          this.allUsers = true;
          this.authority = Authority.TENANT_ADMIN;
          this.tenantId = this.authUser.tenantId.id;
          this.customerId = NULL_UUID;
          this.config.entitiesFetchFunction = pageLink => this.userService.getUsers(pageLink);
          this.config.addEnabled = true;
        }
        this.updateActionCellDescriptors(auth);
      }),
      mergeMap(() => {
        if (this.allUsers) {
          return of({title: ''});
        } else if (this.authority === Authority.TENANT_ADMIN) {
          return this.tenantService.getTenant(this.tenantId);
        } else if (isDefinedAndNotNull(this.customerId)) {
          return this.customerService.getCustomer(this.customerId);
        }
        return of({title: ''});
      }),
      map((parentEntity) => {
        if (this.allUsers) {
          this.config.tableTitle = this.translate.instant('user.users');
        } else if (this.authority === Authority.TENANT_ADMIN) {
          this.config.tableTitle = parentEntity.title + ': ' + this.translate.instant('user.tenant-admins');
        } else {
          this.config.tableTitle = (parentEntity.title ? parentEntity.title + ': ' : '')
            + this.translate.instant('user.customer-users');
        }
        return this.config;
      })
    );
  }

  updateActionCellDescriptors(auth: AuthState) {
    this.config.cellActionDescriptors.splice(0);
    if (auth.userTokenAccessEnabled && auth.userDetails?.authority === Authority.TENANT_ADMIN) {
      this.config.cellActionDescriptors.push(
        {
          name: this.authority === Authority.TENANT_ADMIN ?
            this.translate.instant('user.login-as-tenant-admin') :
            this.translate.instant('user.login-as-customer-user'),
          icon: 'mdi:login',
          // the backend only hands out the token of a customer user to a tenant administrator, so the action
          // stays visible but disabled for the other administrators (same as ThingsBoard PE)
          isEnabled: (user) => this.canLoginAsUser(user),
          onAction: ($event, entity) => this.loginAsUser($event, entity)
        }
      );
    }
  }

  private canLoginAsUser(user: User): boolean {
    return !!user && user.authority === Authority.CUSTOMER_USER;
  }

  saveUser(user: User): Observable<User> {
    user.tenantId = new TenantId(this.tenantId);
    if (!this.allUsers) {
      user.customerId = new CustomerId(this.customerId);
      user.authority = this.authority;
    }
    if (!user.additionalInfo.lang) {
      delete user.additionalInfo.lang;
    }
    if (!user.additionalInfo.unitSystem) {
      delete user.additionalInfo.unitSystem;
    }
    return this.userService.saveUser(user);
  }

  addUser(): Observable<User> {
    return this.dialog.open<AddUserDialogComponent, AddUserDialogData,
      User>(AddUserDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {
        tenantId: this.tenantId,
        customerId: this.customerId,
        authority: this.authority
      }
    }).afterClosed();
  }

  private openUser($event: Event, user: User, config: EntityTableConfig<User>) {
    if ($event) {
      $event.stopPropagation();
    }
    const url = this.router.createUrlTree([user.id.id], {relativeTo: config.getActivatedRoute()});
    this.router.navigateByUrl(url);
  }

  loginAsUser($event: Event, user: User) {
    if ($event) {
      $event.stopPropagation();
    }
    this.authService.loginAsUser(user.id.id).subscribe();
  }

  displayActivationLink($event: Event, user: User) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.getActivationLinkInfo(user.id.id).subscribe(
      (activationLinkInfo) => {
        this.dialog.open<ActivationLinkDialogComponent, ActivationLinkDialogData,
          void>(ActivationLinkDialogComponent, {
          disableClose: true,
          panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
          data: {
            activationLinkInfo
          }
        });
      }
    );
  }

  /**
   * "Manage owner and groups": the owner of the user (the customer that owns it) and the user groups it belongs to,
   * like the dialog of ThingsBoard PE.
   */
  manageOwnerAndGroups($event: Event, user: User, config: EntityTableConfig<User>) {
    if ($event) {
      $event.stopPropagation();
    }
    this.dialog.open<ManageOwnerAndGroupsDialogComponent, ManageOwnerAndGroupsDialogData, boolean>(
      ManageOwnerAndGroupsDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          entityType: EntityType.USER,
          entityId: user.id.id,
          entityName: user.email,
          ownerId: user.customerId ? user.customerId.id : null,
          ownerEditable: user.authority === Authority.CUSTOMER_USER,
          user
        }
      }
    ).afterClosed().subscribe((changed) => {
      if (changed) {
        this.store.dispatch(new ActionNotificationShow(
          {
            message: this.translate.instant('owner-and-groups.saved'),
            type: 'success'
          }));
        config.updateData(false, true);
      }
    });
  }

  resendActivation($event: Event, user: User) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.sendActivationEmail(user.email).subscribe(() => {
      this.store.dispatch(new ActionNotificationShow(
        {
          message: this.translate.instant('user.activation-email-sent-message'),
          type: 'success'
        }));
    });
  }

  setUserCredentialsEnabled($event: Event, user: User, userCredentialsEnabled: boolean) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.setUserCredentialsEnabled(user.id.id, userCredentialsEnabled).subscribe(() => {
      if (!user.additionalInfo) {
        user.additionalInfo = {};
      }
      user.additionalInfo.userCredentialsEnabled = userCredentialsEnabled;
      this.store.dispatch(new ActionNotificationShow(
        {
          message: this.translate.instant(userCredentialsEnabled ? 'user.enable-account-message' : 'user.disable-account-message'),
          type: 'success'
        }));
    });
  }

  onUserAction(action: EntityAction<User>, config: EntityTableConfig<User>): boolean {
    switch (action.action) {
      case 'open':
        this.openUser(action.event, action.entity, config);
        return true;
      case 'loginAsUser':
        this.loginAsUser(action.event, action.entity);
        return true;
      case 'displayActivationLink':
        this.displayActivationLink(action.event, action.entity);
        return true;
      case 'resendActivation':
        this.resendActivation(action.event, action.entity);
        return true;
      case 'disableAccount':
        this.setUserCredentialsEnabled(action.event, action.entity, false);
        return true;
      case 'enableAccount':
        this.setUserCredentialsEnabled(action.event, action.entity, true);
        return true;
      case 'manageOwnerAndGroups':
        this.manageOwnerAndGroups(action.event, action.entity, config);
        return true;
    }
    return false;
  }

}
