// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { DeviceId } from '@shared/models/id/device-id';

export enum EmulatorStatus {
  STOPPED = 'STOPPED',
  RUNNING = 'RUNNING',
  PAUSED = 'PAUSED'
}

export interface EmulatorSignal {
  key: string;
  label: string;
  unit?: string;
  kind: string;
  min?: number;
  max?: number;
  decimals?: number;
  rate?: number;
  values?: string[];
}

export interface EmulatorProfile {
  id: string;
  name: string;
  category: string;
  type: string;
  description: string;
  verified: boolean;
  deviceProfile: string;
  model?: string;
  intervalSeconds: number;
  scenarios: string[];
  defaultScenario: string;
  signals: EmulatorSignal[];
  icon: string;
  color: string;
}

export interface EmulatorCatalogData {
  categories: string[];
  types: string[];
  profiles: EmulatorProfile[];
}

export interface EmulatorInstance {
  id: string;
  profileId: string;
  profileName: string;
  category: string;
  name: string;
  deviceId: DeviceId;
  deviceName: string;
  status: EmulatorStatus;
  scenario: string;
  intervalSeconds: number;
  createdTime: number;
  lastActivityTs: number;
  dashboardId?: string;
  dashboardTitle?: string;
  publishedMessages: number;
}

export interface EmulatorCreateRequest {
  profileId: string;
  name?: string;
  scenario?: string;
  intervalSeconds?: number;
  createDashboard: boolean;
}
