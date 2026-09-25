// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { AutomationService } from '@core/http/automation.service';
import { AutomationRule, AutomationScheduleType, AutomationTriggerType } from '@shared/models/automation.models';
import { AutomationRuleDialogComponent, AutomationRuleDialogData } from '@home/pages/admin/automation-rule-dialog.component';
import { take } from 'rxjs/operators';

@Component({
  selector: 'tb-automation',
  templateUrl: './automation.component.html',
  styleUrls: ['./automation.component.scss'],
  standalone: false
})
export class AutomationComponent extends PageComponent implements OnInit {

  readonly scheduleTypes = AutomationScheduleType;
  readonly triggerTypes = AutomationTriggerType;

  rules: AutomationRule[] = [];
  isLoading = true;

  constructor(protected store: Store<AppState>,
              private automationService: AutomationService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private snackBar: MatSnackBar,
              private translate: TranslateService) {
    super(store);
  }

  ngOnInit(): void {
    this.loadRules();
  }

  get enabledCount(): number {
    return this.rules.filter(rule => rule.enabled).length;
  }

  statusLabel(rule: AutomationRule): string {
    return this.translate.instant(rule.lastStatus === 'OK' ? 'automation.status-ok' : 'automation.status-failed');
  }

  loadRules(): void {
    this.isLoading = true;
    this.automationService.getAutomationRules().subscribe({
      next: (result) => {
        this.rules = (result.rules || []).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
        this.showError('automation.load-error');
      }
    });
  }

  addRule(): void {
    this.openRuleDialog({isAdd: true});
  }

  editRule(rule: AutomationRule): void {
    this.openRuleDialog({isAdd: false, rule});
  }

  deleteRule(rule: AutomationRule): void {
    this.dialogService.confirm('automation.delete-title', this.translate.instant('automation.delete-text', {name: rule.name}))
      .pipe(take(1))
      .subscribe((confirmed) => {
        if (confirmed && rule.id) {
          this.automationService.deleteAutomationRule(rule.id).subscribe(
            () => {
              this.showSuccess('automation.deleted');
              this.loadRules();
            },
            () => this.showError('automation.save-error')
          );
        }
      });
  }

  runNow(rule: AutomationRule): void {
    if (!rule.id) {
      return;
    }
    this.automationService.runAutomationRuleNow(rule.id).subscribe(
      () => {
        this.showSuccess('automation.run-sent');
        this.loadRules();
      },
      () => this.showError('automation.run-error')
    );
  }

  onEnabledChange(rule: AutomationRule, enabled: boolean): void {
    if (!rule.id) {
      return;
    }
    this.automationService.setAutomationRuleEnabled(rule.id, enabled).subscribe(
      (updated) => {
        rule.enabled = updated.enabled;
        rule.nextRunTs = updated.nextRunTs;
        this.showSuccess(enabled ? 'automation.enabled-msg' : 'automation.disabled-msg');
      },
      () => {
        rule.enabled = !enabled;
        this.showError('automation.save-error');
      }
    );
  }

  scheduleDescription(rule: AutomationRule): string {
    const schedule = rule.schedule;
    const tz = schedule?.timeZone ? ` (${schedule.timeZone})` : '';
    switch (rule.triggerType) {
      case AutomationTriggerType.INTERVAL: {
        const unit = rule.interval?.unit === 'MINUTES' ? 'min' : 'h';
        let text = `${this.translate.instant('automation.every')} ${rule.interval?.value || 1} ${unit}`;
        if (rule.interval?.fromTime && rule.interval?.toTime) {
          text += ` (${rule.interval.fromTime}-${rule.interval.toTime})`;
        }
        return text;
      }
      case AutomationTriggerType.TELEMETRY:
        return `${rule.condition?.key} ${this.operatorLabel(rule.condition?.operator)} ${rule.condition?.value}`
          + (rule.condition?.forSeconds ? ` / ${rule.condition.forSeconds}s` : '')
          + (rule.condition?.cooldownMinutes ? ` / cool ${rule.condition.cooldownMinutes}min` : '');
      case AutomationTriggerType.DEVICE_STATE:
        return this.translate.instant(rule.deviceState?.state === 'ONLINE'
          ? 'automation.state-online' : 'automation.state-offline');
      case AutomationTriggerType.ALARM:
        return `${rule.alarm?.alarmType} - ${this.translate.instant(rule.alarm?.event === 'CLEARED'
          ? 'automation.alarm-cleared' : 'automation.alarm-active')}`;
      default:
        return this.scheduleText(schedule, tz);
    }
  }

  private scheduleText(schedule: any, tz: string): string {
    if (!schedule) {
      return '';
    }
    switch (schedule.type) {
      case AutomationScheduleType.CRON:
        return `${this.translate.instant('automation.type-cron')}: ${schedule.cron}${tz}`;
      case AutomationScheduleType.ASTRONOMY:
        return `${this.translate.instant(schedule.astronomyEvent === 'SUNSET' ? 'automation.sunset' : 'automation.sunrise')}`
          + (schedule.offsetMinutes ? ` ${schedule.offsetMinutes > 0 ? '+' : ''}${schedule.offsetMinutes}min` : '');
      case AutomationScheduleType.WEEKLY: {
        const days = (schedule.daysOfWeek || [])
          .map(d => this.translate.instant('automation.day-' + ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'][d - 1]))
          .join(', ');
        return `${days} ${schedule.time}${tz}`;
      }
      default:
        return `${this.translate.instant('automation.type-daily')} ${schedule.time}${tz}`;
    }
  }

  private operatorLabel(operator: string): string {
    switch ((operator || 'EQ').toUpperCase()) {
      case 'GT': return '>';
      case 'GTE': return '>=';
      case 'LT': return '<';
      case 'LTE': return '<=';
      case 'NE': return '!=';
      default: return '=';
    }
  }

  private openRuleDialog(data: AutomationRuleDialogData): void {
    this.dialog.open<AutomationRuleDialogComponent, AutomationRuleDialogData, AutomationRule>(
      AutomationRuleDialogComponent,
      {disableClose: true, panelClass: 'tb-automation-dialog', data}
    ).afterClosed().subscribe((rule) => {
      if (rule) {
        this.automationService.saveAutomationRule(rule).subscribe(
          () => {
            this.showSuccess('automation.saved');
            this.loadRules();
          },
          () => this.showError('automation.save-error')
        );
      }
    });
  }

  private showSuccess(key: string): void {
    this.snackBar.open(this.translate.instant(key), null, {duration: 3000, panelClass: ['tb-success']});
  }

  private showError(key: string): void {
    this.snackBar.open(this.translate.instant(key), null, {duration: 5000, panelClass: ['tb-error']});
  }
}
