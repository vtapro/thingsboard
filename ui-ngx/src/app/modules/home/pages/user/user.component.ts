// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, Inject, OnInit, Optional } from '@angular/core';
import { select, Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityComponent } from '../../components/entity/entity.component';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { User } from '@shared/models/user.model';
import { selectAuth } from '@core/auth/auth.selectors';
import { map, take } from 'rxjs/operators';
import { Authority } from '@shared/models/authority.enum';
import { isDefinedAndNotNull, validateEmail } from '@core/utils';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { ActionNotificationShow } from '@app/core/notification/notification.actions';
import { TranslateService } from '@ngx-translate/core';
import { environment as env } from '@env/environment';
import { UnitSystems } from '@shared/models/unit.models';

@Component({
    selector: 'tb-user',
    templateUrl: './user.component.html',
    styleUrls: ['./user.component.scss'],
    standalone: false
})
export class UserComponent extends EntityComponent<User> implements OnInit {

  authority = Authority;
  languageList = env.supportedLangs;
  UnitSystems = UnitSystems;

  loginAsUserEnabled$ = this.store.pipe(
    select(selectAuth),
    // only a tenant administrator may impersonate a user (the endpoint is restricted to that authority)
    map((auth) => auth.userTokenAccessEnabled && auth.userDetails?.authority === Authority.TENANT_ADMIN)
  );

  /** "Manage owner and groups" is the tenant administrator action of ThingsBoard PE. */
  manageOwnerAndGroupsEnabled$ = this.store.pipe(
    select(selectAuth),
    map((auth) => auth.userDetails?.authority === Authority.TENANT_ADMIN)
  );

  private authUserAuthority: Authority;

  constructor(protected store: Store<AppState>,
              @Optional() @Inject('entity') protected entityValue: User,
              @Optional() @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<User>,
              public fb: UntypedFormBuilder,
              protected cd: ChangeDetectorRef,
              protected translate: TranslateService) {
    super(store, fb, entityValue, entitiesTableConfigValue, cd);
  }

  ngOnInit(): void {
    super.ngOnInit();
    this.store.pipe(select(selectAuth), take(1)).subscribe(auth => this.authUserAuthority = auth.userDetails?.authority);
  }

  /**
   * A tenant administrator account is provisioned by the system administrator: disabling it locks the whole
   * tenant out, so only the system administrator may do it (same rule the backend enforces).
   */
  canDisableAccount(): boolean {
    if (this.entity?.authority !== Authority.TENANT_ADMIN) {
      return true;
    }
    return this.authUserAuthority === Authority.SYS_ADMIN;
  }

  /**
   * The impersonation endpoint only returns the token of a customer user to a tenant administrator, so the button
   * is shown (ThingsBoard PE greys it out instead of hiding it) but stays disabled for the other administrators.
   */
  canLoginAsUser(): boolean {
    return this.entity?.authority === Authority.CUSTOMER_USER;
  }

  /**
   * The owner of a user is the customer that owns it: only customer users have an owner, so the field of the
   * "Manage owner and groups" dialog is shown only for them.
   */
  canChangeOwner(): boolean {
    return this.entity?.authority === Authority.CUSTOMER_USER;
  }

  hideDelete() {
    if (this.entitiesTableConfig) {
      return !this.entitiesTableConfig.deleteEnabled(this.entity);
    } else {
      return false;
    }
  }

  isUserCredentialsEnabled(): boolean {
      return this.entity?.additionalInfo?.userCredentialsEnabled === true;
  }

  isUserActivated(): boolean {
    return this.entity?.additionalInfo?.userActivated === true;
  }

  buildForm(entity: User): UntypedFormGroup {
    return this.fb.group(
      {
        email: [entity ? entity.email : '', [Validators.required, validateEmail]],
        firstName: [entity ? entity.firstName : ''],
        lastName: [entity ? entity.lastName : ''],
        phone: [entity ? entity.phone : ''],
        additionalInfo: this.fb.group(
          {
            description: [entity && entity.additionalInfo ? entity.additionalInfo.description : ''],
            lang: [entity && entity.additionalInfo ? entity.additionalInfo.lang : null],
            unitSystem: [entity && entity.additionalInfo ? entity.additionalInfo.unitSystem : null],
            defaultDashboardId: [entity && entity.additionalInfo ? entity.additionalInfo.defaultDashboardId : null],
            defaultDashboardFullscreen: [entity && entity.additionalInfo ? entity.additionalInfo.defaultDashboardFullscreen : false],
            homeDashboardId: [entity && entity.additionalInfo ? entity.additionalInfo.homeDashboardId : null],
            homeDashboardHideToolbar: [entity && entity.additionalInfo &&
            isDefinedAndNotNull(entity.additionalInfo.homeDashboardHideToolbar) ? entity.additionalInfo.homeDashboardHideToolbar : true]
          }
        )
      }
    );
  }

  updateForm(entity: User) {
    this.entityForm.patchValue({email: entity.email});
    this.entityForm.patchValue({firstName: entity.firstName});
    this.entityForm.patchValue({lastName: entity.lastName});
    this.entityForm.patchValue({phone: entity.phone});
    this.entityForm.patchValue({additionalInfo: {description: entity.additionalInfo ? entity.additionalInfo.description : ''}});
    this.entityForm.patchValue({additionalInfo:
        {lang: entity.additionalInfo ? entity.additionalInfo.lang : null}});
    this.entityForm.patchValue({additionalInfo:
        {unitSystem: entity.additionalInfo ? entity.additionalInfo.unitSystem : null}});
    this.entityForm.patchValue({additionalInfo:
        {defaultDashboardId: entity.additionalInfo ? entity.additionalInfo.defaultDashboardId : null}});
    this.entityForm.patchValue({additionalInfo:
        {defaultDashboardFullscreen: entity.additionalInfo ? entity.additionalInfo.defaultDashboardFullscreen : false}});
    this.entityForm.patchValue({additionalInfo:
        {homeDashboardId: entity.additionalInfo ? entity.additionalInfo.homeDashboardId : null}});
    this.entityForm.patchValue({additionalInfo:
        {homeDashboardHideToolbar: entity.additionalInfo &&
          isDefinedAndNotNull(entity.additionalInfo.homeDashboardHideToolbar) ? entity.additionalInfo.homeDashboardHideToolbar : true}});
  }

  onUserIdCopied($event) {
    this.store.dispatch(new ActionNotificationShow(
      {
        message: this.translate.instant('user.idCopiedMessage'),
        type: 'success',
        duration: 750,
        verticalPosition: 'bottom',
        horizontalPosition: 'right'
      }
    ));
  }

}
