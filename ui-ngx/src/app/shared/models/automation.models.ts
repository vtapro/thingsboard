// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { EntityId } from '@shared/models/id/entity-id';

export enum AutomationScheduleType {
  DAILY = 'DAILY',
  WEEKLY = 'WEEKLY',
  CRON = 'CRON'
}

export interface AutomationSchedule {
  timeZone: string;
  type: AutomationScheduleType;
  time: string;
  daysOfWeek: number[];
  cron?: string;
}

export interface AutomationRun {
  ts: number;
  status: string;
  message?: string;
}

export interface AutomationRule {
  id?: string;
  name: string;
  enabled: boolean;
  deviceId?: EntityId;
  deviceName?: string;
  method: string;
  params?: any;
  oneWay: boolean;
  persistent: boolean;
  schedule: AutomationSchedule;
  lastRunTs?: number;
  lastStatus?: string;
  lastMessage?: string;
  nextRunTs?: number;
  runs?: AutomationRun[];
}

export interface AutomationRules {
  rules: AutomationRule[];
}

export const automationTimeZones: string[] = [
  'Asia/Ho_Chi_Minh',
  'Asia/Bangkok',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Asia/Shanghai',
  'Europe/London',
  'Europe/Berlin',
  'America/New_York',
  'UTC'
];

export const automationDaysOfWeek: Array<{ value: number; label: string }> = [
  { value: 1, label: 'automation.day-mon' },
  { value: 2, label: 'automation.day-tue' },
  { value: 3, label: 'automation.day-wed' },
  { value: 4, label: 'automation.day-thu' },
  { value: 5, label: 'automation.day-fri' },
  { value: 6, label: 'automation.day-sat' },
  { value: 7, label: 'automation.day-sun' }
];
