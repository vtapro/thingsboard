// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { EntityType } from '@shared/models/entity-type.models';
import {
  AutomationRule,
  AutomationScheduleType,
  automationDaysOfWeek,
  automationTimeZones
} from '@shared/models/automation.models';
import { EntityId } from '@shared/models/id/entity-id';

export interface AutomationRuleDialogData {
  rule?: AutomationRule;
  isAdd: boolean;
}

@Component({
  selector: 'tb-automation-rule-dialog',
  templateUrl: './automation-rule-dialog.component.html',
  standalone: false
})
export class AutomationRuleDialogComponent extends DialogComponent<AutomationRuleDialogComponent, AutomationRule>
  implements OnInit {

  readonly entityType = EntityType;
  readonly scheduleTypes = AutomationScheduleType;
  readonly timeZones = automationTimeZones;
  readonly daysOfWeek = automationDaysOfWeek;

  ruleForm: FormGroup;
  paramsJson = '{\n  "state": "ON"\n}';
  paramsError = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: AutomationRuleDialogData,
              public dialogRef: MatDialogRef<AutomationRuleDialogComponent, AutomationRule>,
              private fb: FormBuilder) {
    super(store, router, dialogRef);
  }

  ngOnInit(): void {
    const rule = this.data.rule;
    const timeZone = rule?.schedule?.timeZone ||
      (Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Ho_Chi_Minh');
    const days = rule?.schedule?.daysOfWeek?.length ? rule.schedule.daysOfWeek : [1, 2, 3, 4, 5, 6, 7];
    this.ruleForm = this.fb.group({
      name: [rule?.name || '', [Validators.required, Validators.maxLength(255)]],
      deviceId: [rule?.deviceId || null, [Validators.required]],
      method: [rule?.method || 'setState', [Validators.required]],
      oneWay: [rule ? rule.oneWay : true],
      persistent: [rule ? rule.persistent : false],
      enabled: [rule ? rule.enabled : true],
      scheduleType: [rule?.schedule?.type || AutomationScheduleType.DAILY, [Validators.required]],
      time: [rule?.schedule?.time || '06:00', [Validators.required]],
      cron: [rule?.schedule?.cron || '0 0 6 * * *'],
      timeZone: [timeZone, [Validators.required]],
      daysOfWeek: [days]
    });
    if (rule?.params) {
      this.paramsJson = JSON.stringify(rule.params, null, 2);
    }
    this.ruleForm.get('scheduleType').valueChanges.subscribe(() => this.ruleForm.updateValueAndValidity());
  }

  get scheduleType(): AutomationScheduleType {
    return this.ruleForm.get('scheduleType').value;
  }

  isDaySelected(day: number): boolean {
    const days: number[] = this.ruleForm.get('daysOfWeek').value || [];
    return days.includes(day);
  }

  toggleDay(day: number): void {
    const days: number[] = [...(this.ruleForm.get('daysOfWeek').value || [])];
    const index = days.indexOf(day);
    if (index >= 0) {
      days.splice(index, 1);
    } else {
      days.push(day);
    }
    this.ruleForm.get('daysOfWeek').setValue(days.sort((a, b) => a - b));
  }

  onParamsChanged(value: string): void {
    this.paramsJson = value;
    this.paramsError = false;
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  save(): void {
    let params: any = {};
    if (this.paramsJson && this.paramsJson.trim().length) {
      try {
        params = JSON.parse(this.paramsJson);
      } catch (e) {
        this.paramsError = true;
        return;
      }
    }
    if (this.ruleForm.invalid) {
      return;
    }
    const form = this.ruleForm.value;
    const rule: AutomationRule = {
      id: this.data.rule?.id,
      name: form.name,
      enabled: form.enabled,
      deviceId: form.deviceId as EntityId,
      method: form.method,
      params,
      oneWay: form.oneWay,
      persistent: form.persistent,
      schedule: {
        type: form.scheduleType,
        timeZone: form.timeZone,
        time: form.time,
        daysOfWeek: form.scheduleType === AutomationScheduleType.WEEKLY ? form.daysOfWeek : [],
        cron: form.scheduleType === AutomationScheduleType.CRON ? form.cron : undefined
      },
      runs: this.data.rule?.runs
    };
    this.dialogRef.close(rule);
  }
}
