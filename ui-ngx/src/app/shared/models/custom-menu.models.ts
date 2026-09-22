// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0

export interface CustomMenuItem {
  id: string;
  name: string;
  icon?: string;
  type: 'dashboard' | 'url';
  target: string;
  assigneeType?: string;
  order?: number;
}

export interface CustomMenuSettings {
  items: CustomMenuItem[];
}

export const customMenuAssigneeTypes: Array<{value: string; label: string}> = [
  {value: '', label: 'Not assigned'},
  {value: 'TENANT_ADMIN', label: 'Tenant administrator'},
  {value: 'CUSTOMER_USER', label: 'Customer user'}
];

