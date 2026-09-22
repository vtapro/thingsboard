// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0

export interface MailTemplateSettings {
  useSystemMailTemplates: boolean;
  templates: { [templateKey: string]: MailTemplate };
}

export interface MailTemplate {
  subject?: string;
  body?: string;
}

export const mailTemplateKeys: string[] = [
  'activation.ftl',
  'account.activated.ftl',
  'reset.password.ftl',
  'password.was.reset.ftl',
  'account.lockout.ftl',
  '2fa.verification.code.ftl',
  'state.enabled.ftl',
  'state.warning.ftl',
  'state.disabled.ftl',
  'test.ftl'
];

