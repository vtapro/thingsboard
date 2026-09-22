// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { MailTemplateService } from '@core/http/mail-template.service';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { PageComponent } from '@shared/components/page.component';
import { MailTemplateSettings, mailTemplateKeys } from '@shared/models/mail-template.models';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { EditorOptions } from 'hugerte';
import { defaultHugeRteOptions } from '@shared/models/hugerte/hugerte.models';

@Component({
    selector: 'tb-mail-templates',
    templateUrl: './mail-templates.component.html',
    styleUrls: ['./mail-templates.component.scss', './settings-card.scss'],
    standalone: false
})
export class MailTemplatesComponent extends PageComponent implements OnInit, HasConfirmForm {

  readonly templateKeys = mailTemplateKeys;

  readonly templateLabels: { [key: string]: string } = {
    'test.ftl': 'Test email message',
    'activation.ftl': 'Account activation message',
    'account.activated.ftl': 'Account activated message',
    'account.lockout.ftl': 'Account lockout message',
    'reset.password.ftl': 'Reset password message',
    'password.was.reset.ftl': 'Password was reset message',
    '2fa.verification.code.ftl': 'Two-factor authentication code message',
    'state.enabled.ftl': 'API usage limit enabled message',
    'state.warning.ftl': 'API usage limit warning message',
    'state.disabled.ftl': 'API usage limit disabled message'
  };

  settingsForm: FormGroup;
  subjectControl = new FormControl('');
  bodyControl = new FormControl('');

  selectedTemplate = mailTemplateKeys[0];

  hugeRteOptions: Partial<EditorOptions> = defaultHugeRteOptions({
    height: 420,
    menubar: true,
    plugins: 'anchor autolink charmap code fullscreen image link lists searchreplace table visualblocks wordcount',
    toolbar: 'undo redo | blocks fontfamily fontsize | bold italic underline strikethrough | link image table | ' +
      'alignleft aligncenter alignright | bullist numlist | code fullscreen',
    valid_elements: '*[*]'
  });

  private templates: { [key: string]: { subject?: string; body?: string } } = {};

  constructor(protected store: Store<AppState>,
              private fb: FormBuilder,
              private mailTemplateService: MailTemplateService) {
    super();
  }

  ngOnInit() {
    this.settingsForm = this.fb.group({
      useSystemMailTemplates: [true]
    });
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.mailTemplateService.getMailTemplateSettings().subscribe(settings => this.setSettings(settings));
    }
  }

  confirmForm(): FormGroup {
    return this.settingsForm;
  }

  templateLabel(templateKey: string): string {
    return this.templateLabels[templateKey] || templateKey;
  }

  setSettings(settings: MailTemplateSettings) {
    this.templates = settings?.templates ? {...settings.templates} : {};
    this.settingsForm.reset({
      useSystemMailTemplates: settings?.useSystemMailTemplates ?? true
    });
    this.selectTemplate(this.selectedTemplate);
  }

  selectTemplate(templateKey: string) {
    this.storeTemplateValues();
    this.selectedTemplate = templateKey;
    const template = this.templates[templateKey];
    this.subjectControl.setValue(template?.subject ?? null);
    this.bodyControl.setValue(template?.body ?? null);
  }

  save() {
    this.storeTemplateValues();
    const settings: MailTemplateSettings = {
      useSystemMailTemplates: this.settingsForm.get('useSystemMailTemplates').value,
      templates: this.templates
    };
    this.mailTemplateService.saveMailTemplateSettings(settings).subscribe(saved => this.setSettings(saved));
  }

  private storeTemplateValues() {
    const current = this.templates[this.selectedTemplate] || {};
    this.templates[this.selectedTemplate] = {
      ...current,
      subject: this.subjectControl.value,
      body: this.bodyControl.value
    };
  }

}
