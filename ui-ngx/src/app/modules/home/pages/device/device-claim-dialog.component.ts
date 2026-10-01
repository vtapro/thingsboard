// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { DeviceService } from '@core/http/device.service';
import { ClaimResponse } from '@shared/models/device.models';

/**
 * Claiming of a device that the provider pre-registered for the customer: the user enters the name of the device and
 * the secret printed on its label, the platform then assigns the device to the customer of the user. Unlike the
 * "Add device" flow this needs no access token, so it is the flow for end users that buy a device.
 */
@Component({
    selector: 'tb-device-claim-dialog',
    templateUrl: './device-claim-dialog.component.html',
    styleUrls: ['./device-claim-dialog.component.scss'],
    standalone: false
})
export class DeviceClaimDialogComponent extends DialogComponent<DeviceClaimDialogComponent, boolean> {

  claimFormGroup: FormGroup;
  errorMessage: string;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              public dialogRef: MatDialogRef<DeviceClaimDialogComponent, boolean>,
              private fb: FormBuilder,
              private deviceService: DeviceService) {
    super(store, router, dialogRef);
    this.claimFormGroup = this.fb.group({
      deviceName: ['', [Validators.required, Validators.maxLength(255)]],
      secretKey: ['', [Validators.maxLength(255)]]
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  claim(): void {
    if (this.claimFormGroup.invalid || this.claimFormGroup.disabled) {
      return;
    }
    this.errorMessage = null;
    this.claimFormGroup.disable({emitEvent: false});
    const {deviceName, secretKey} = this.claimFormGroup.getRawValue();
    this.deviceService.claimDevice(deviceName, {secretKey}).subscribe({
      next: (result) => {
        this.claimFormGroup.enable({emitEvent: false});
        if (result && (result.response === ClaimResponse.SUCCESS || result.response === ClaimResponse.CLAIMED)) {
          this.dialogRef.close(true);
        } else {
          this.errorMessage = 'device.claim-failure';
        }
      },
      error: () => {
        this.claimFormGroup.enable({emitEvent: false});
        this.errorMessage = 'device.claim-failure';
      }
    });
  }

}
