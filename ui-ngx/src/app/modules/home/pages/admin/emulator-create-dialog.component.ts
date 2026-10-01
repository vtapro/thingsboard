// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { EmulatorService } from '@core/http/emulator.service';
import { EmulatorInstance, EmulatorProfile } from '@shared/models/emulator.models';

export interface EmulatorCreateDialogData {
  profile: EmulatorProfile;
}

@Component({
  selector: 'tb-emulator-create-dialog',
  templateUrl: './emulator-create-dialog.component.html',
  styleUrls: ['./emulator-create-dialog.component.scss'],
  standalone: false
})
export class EmulatorCreateDialogComponent {

  readonly intervals = [
    {value: 5, label: '5 s'},
    {value: 10, label: '10 s'},
    {value: 30, label: '30 s'},
    {value: 60, label: '1 m'},
    {value: 300, label: '5 m'}
  ];

  emulatorForm: UntypedFormGroup;
  isSaving = false;

  constructor(private dialogRef: MatDialogRef<EmulatorCreateDialogComponent, EmulatorInstance>,
              @Inject(MAT_DIALOG_DATA) public data: EmulatorCreateDialogData,
              private fb: UntypedFormBuilder,
              private emulatorService: EmulatorService) {
    this.emulatorForm = this.fb.group({
      name: [data.profile.id + '-001', [Validators.required, Validators.pattern(/^[^\s]+$/)]],
      scenario: [data.profile.defaultScenario, []],
      intervalSeconds: [data.profile.intervalSeconds, [Validators.required]],
      createDashboard: [true, []]
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  create(): void {
    if (this.emulatorForm.invalid || this.isSaving) {
      return;
    }
    this.isSaving = true;
    const value = this.emulatorForm.value;
    this.emulatorService.createEmulator({
      profileId: this.data.profile.id,
      name: value.name,
      scenario: value.scenario,
      intervalSeconds: value.intervalSeconds,
      createDashboard: value.createDashboard
    }).subscribe({
      next: (created) => this.dialogRef.close(created),
      error: () => {
        this.isSaving = false;
        this.emulatorForm.get('name').setErrors({duplicate: true});
      }
    });
  }

}
