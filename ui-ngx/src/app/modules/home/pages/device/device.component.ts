// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, Inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityComponent } from '../../components/entity/entity.component';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import {
  createDeviceConfiguration,
  createDeviceTransportConfiguration, DeviceCredentials,
  DeviceData,
  DeviceInfo,
  DeviceProfileInfo,
  DeviceProfileType,
  DeviceTransportType
} from '@shared/models/device.models';
import { EntityType } from '@shared/models/entity-type.models';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { TranslateService } from '@ngx-translate/core';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { Subject } from 'rxjs';
import { OtaUpdateType } from '@shared/models/ota-package.models';
import { distinctUntilChanged } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { WhiteLabelingService } from '@core/http/white-labeling.service';
import { hasRbacPermission } from '@core/services/rbac-permissions';
import { MatDialog } from '@angular/material/dialog';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { EntityShareDialogComponent } from '@home/components/entity/entity-share-dialog.component';

@Component({
    selector: 'tb-device',
    templateUrl: './device.component.html',
    styleUrls: ['./device.component.scss'],
    standalone: false
})
export class DeviceComponent extends EntityComponent<DeviceInfo> {

  entityType = EntityType;

  deviceCredentials$: Subject<DeviceCredentials>;

  deviceScope: 'tenant' | 'customer' | 'customer_user' | 'edge' | 'edge_customer_user';

  otaUpdateType = OtaUpdateType;

  hideConnectivityDialog = false;

  /** The credentials dialog loads the credentials of the device, so it requires READ_CREDENTIALS on DEVICE. */
  get canViewCredentials(): boolean {
    return hasRbacPermission('DEVICE', 'READ_CREDENTIALS');
  }

  /** The connectivity dialog sends an RPC to the device. */
  get canCallRpc(): boolean {
    return hasRbacPermission('DEVICE', 'RPC_CALL');
  }

  /** Only the tenant administrator may share an entity (the API requires TENANT_ADMIN). */
  get canShareEntity(): boolean {
    return getCurrentAuthUser(this.store)?.authority === Authority.TENANT_ADMIN;
  }

  openShareDialog(): void {
    this.dialog.open(EntityShareDialogComponent, {
      data: {entityType: 'DEVICE', entityId: this.entity.id.id},
      width: '600px'
    });
  }

  constructor(protected store: Store<AppState>,
              protected translate: TranslateService,
              @Inject('entity') protected entityValue: DeviceInfo,
              @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<DeviceInfo>,
              public fb: UntypedFormBuilder,
              protected cd: ChangeDetectorRef,
              private destroyRef: DestroyRef,
              private dialog: MatDialog,
              private whiteLabelingService: WhiteLabelingService) {
    super(store, fb, entityValue, entitiesTableConfigValue, cd);
    this.whiteLabelingService.settings$.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(settings => {
      this.hideConnectivityDialog = settings.enabled && settings.hideConnectivityDialog;
    });
  }

  ngOnInit() {
    this.deviceScope = this.entitiesTableConfig.componentsData.deviceScope;
    this.deviceCredentials$ = this.entitiesTableConfigValue.componentsData.deviceCredentials$;
    super.ngOnInit();
  }

  hideDelete() {
    if (this.entitiesTableConfig) {
      return !this.entitiesTableConfig.deleteEnabled(this.entity);
    } else {
      return false;
    }
  }

  isAssignedToCustomer(entity: DeviceInfo): boolean {
    return entity && entity.customerId && entity.customerId.id !== NULL_UUID;
  }

  buildForm(entity: DeviceInfo): UntypedFormGroup {
    const form = this.fb.group(
      {
        name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255)]],
        deviceProfileId: [entity ? entity.deviceProfileId : null, [Validators.required]],
        firmwareId: [entity ? entity.firmwareId : null],
        softwareId: [entity ? entity.softwareId : null],
        label: [entity ? entity.label : '', [Validators.maxLength(255)]],
        deviceData: [entity ? entity.deviceData : null, [Validators.required]],
        additionalInfo: this.fb.group(
          {
            gateway: [entity && entity.additionalInfo ? entity.additionalInfo.gateway : false],
            overwriteActivityTime: [entity && entity.additionalInfo ? entity.additionalInfo.overwriteActivityTime : false],
            description: [entity && entity.additionalInfo ? entity.additionalInfo.description : ''],
          }
        )
      }
    );
    form.get('deviceProfileId').valueChanges.pipe(
      distinctUntilChanged((prev, curr) => prev?.id === curr?.id),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(profileId => {
      if (profileId && this.isEdit) {
        this.entityForm.patchValue({
          firmwareId: null,
          softwareId: null
        }, {emitEvent: false});
      }
    });
    return form;
  }

  updateForm(entity: DeviceInfo) {
    this.entityForm.patchValue({
      name: entity.name,
      deviceProfileId: entity.deviceProfileId,
      firmwareId: entity.firmwareId,
      softwareId: entity.softwareId,
      label: entity.label,
      deviceData: entity.deviceData,
      additionalInfo: {
        gateway: entity.additionalInfo ? entity.additionalInfo.gateway : false,
        overwriteActivityTime: entity.additionalInfo ? entity.additionalInfo.overwriteActivityTime : false,
        description: entity.additionalInfo ? entity.additionalInfo.description : ''
      }
    });
  }


  onDeviceIdCopied($event) {
    this.store.dispatch(new ActionNotificationShow(
      {
        message: this.translate.instant('device.idCopiedMessage'),
        type: 'success',
        duration: 750,
        verticalPosition: 'bottom',
        horizontalPosition: 'right'
      }));
  }

  onDeviceProfileUpdated() {
    this.entitiesTableConfig.updateData(false, false);
  }

  onDeviceProfileChanged(deviceProfile: DeviceProfileInfo) {
    if (deviceProfile && this.isEdit) {
      const deviceProfileType: DeviceProfileType = deviceProfile.type;
      const deviceTransportType: DeviceTransportType = deviceProfile.transportType;
      let deviceData: DeviceData = this.entityForm.getRawValue().deviceData;
      if (!deviceData) {
        deviceData = {
          configuration: createDeviceConfiguration(deviceProfileType),
          transportConfiguration: createDeviceTransportConfiguration(deviceTransportType)
        };
        this.entityForm.patchValue({deviceData});
        this.entityForm.markAsDirty();
      } else {
        let changed = false;
        if (deviceData.configuration.type !== deviceProfileType) {
          deviceData.configuration = createDeviceConfiguration(deviceProfileType);
          changed = true;
        }
        if (deviceData.transportConfiguration.type !== deviceTransportType) {
          deviceData.transportConfiguration = createDeviceTransportConfiguration(deviceTransportType);
          changed = true;
        }
        if (changed) {
          this.entityForm.patchValue({deviceData});
          this.entityForm.markAsDirty();
        }
      }
    }
  }
}
