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
  AutomationTriggerType,
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
  styleUrls: ['./automation-rule-dialog.component.scss'],
  standalone: false
})
export class AutomationRuleDialogComponent extends DialogComponent<AutomationRuleDialogComponent, AutomationRule>
  implements OnInit {

  readonly entityType = EntityType;
  readonly scheduleTypes = AutomationScheduleType;
  readonly triggerTypes = AutomationTriggerType;
  readonly timeZones = automationTimeZones;
  readonly daysOfWeek = automationDaysOfWeek;

  ruleForm: FormGroup;
  paramsJson = '{\n  "state": "ON"\n}';
  offParamsJson = '{\n  "state": "OFF"\n}';
  paramsError = false;
  offParamsError = false;

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
    if (rule?.params) {
      this.paramsJson = JSON.stringify(rule.params, null, 2);
    }
    if (rule?.offParams) {
      this.offParamsJson = JSON.stringify(rule.offParams, null, 2);
    }
    this.ruleForm = this.fb.group({
      name: [rule?.name || '', [Validators.required, Validators.maxLength(255)]],
      enabled: [rule ? rule.enabled : true, [Validators.required]],
      deviceId: [rule?.deviceId || null, [Validators.required]],
      method: [rule?.method || 'setState', [Validators.required]],
      oneWay: [rule ? rule.oneWay : true],
      persistent: [rule ? rule.persistent : false],
      durationMinutes: [rule?.durationMinutes || 0],
      offParamsJson: [this.offParamsJson],

      triggerType: [rule?.triggerType || AutomationTriggerType.SCHEDULE, [Validators.required]],

      // schedule
      scheduleType: [rule?.schedule?.type || AutomationScheduleType.DAILY, [Validators.required]],
      time: [rule?.schedule?.time || '06:00'],
      cron: [rule?.schedule?.cron || '0 0 6 * * *'],
      timeZone: [timeZone, [Validators.required]],
      daysOfWeek: [days],
      astronomyEvent: [rule?.schedule?.astronomyEvent || 'SUNRISE'],
      latitude: [rule?.schedule?.latitude ?? 10.8231],
      longitude: [rule?.schedule?.longitude ?? 106.6297],
      offsetMinutes: [rule?.schedule?.offsetMinutes || 0],

      // interval
      intervalValue: [rule?.interval?.value || 2],
      intervalUnit: [rule?.interval?.unit || 'HOURS'],
      intervalFrom: [rule?.interval?.fromTime || ''],
      intervalTo: [rule?.interval?.toTime || ''],

      // telemetry condition
      conditionKey: [rule?.condition?.key || '', []],
      conditionOperator: [rule?.condition?.operator || 'LT'],
      conditionValue: [rule?.condition?.value || 0],
      conditionForSeconds: [rule?.condition?.forSeconds || 0],
      conditionCooldownMinutes: [rule?.condition?.cooldownMinutes || 0],

      // device state & alarm
      deviceState: [rule?.deviceState?.state || 'OFFLINE'],
      alarmType: [rule?.alarm?.alarmType || ''],
      alarmEvent: [rule?.alarm?.event || 'ACTIVE']
    });
  }

  get triggerType(): AutomationTriggerType {
    return this.ruleForm.get('triggerType').value;
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
    const params = this.parseJson(this.paramsJson);
    if (params === undefined) {
      this.paramsError = true;
      return;
    }
    const offParams = this.parseJson(this.offParamsJson);
    if (offParams === undefined) {
      this.offParamsError = true;
      return;
    }
    if (this.ruleForm.invalid) {
      return;
    }
    const f = this.ruleForm.value;
    const rule: AutomationRule = {
      id: this.data.rule?.id,
      name: f.name,
      enabled: f.enabled,
      deviceId: f.deviceId as EntityId,
      method: f.method,
      params,
      oneWay: f.oneWay,
      persistent: f.persistent,
      durationMinutes: f.durationMinutes || 0,
      offParams: offParams && Object.keys(offParams).length ? offParams : undefined,
      triggerType: f.triggerType,
      schedule: {
        type: f.scheduleType,
        timeZone: f.timeZone,
        time: f.time,
        daysOfWeek: f.scheduleType === AutomationScheduleType.WEEKLY ? f.daysOfWeek : [],
        cron: f.scheduleType === AutomationScheduleType.CRON ? f.cron : undefined,
        astronomyEvent: f.astronomyEvent,
        latitude: Number(f.latitude),
        longitude: Number(f.longitude),
        offsetMinutes: Number(f.offsetMinutes) || 0
      },
      interval: {
        value: Number(f.intervalValue) || 1,
        unit: f.intervalUnit,
        fromTime: f.intervalFrom || undefined,
        toTime: f.intervalTo || undefined
      },
      condition: {
        key: f.conditionKey,
        operator: f.conditionOperator,
        value: Number(f.conditionValue) || 0,
        forSeconds: Number(f.conditionForSeconds) || 0,
        cooldownMinutes: Number(f.conditionCooldownMinutes) || 0
      },
      deviceState: { state: f.deviceState },
      alarm: { alarmType: f.alarmType, event: f.alarmEvent },
      runs: this.data.rule?.runs
    };
    this.dialogRef.close(rule);
  }

  private parseJson(value: string): any {
    if (!value || !value.trim().length) {
      return {};
    }
    try {
      return JSON.parse(value);
    } catch (e) {
      return undefined;
    }
  }
}
