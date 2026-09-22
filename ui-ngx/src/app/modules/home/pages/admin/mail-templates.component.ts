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

@Component({
    selector: 'tb-mail-templates',
    templateUrl: './mail-templates.component.html',
    styleUrls: ['./mail-templates.component.scss', './settings-card.scss'],
    standalone: false
})
export class MailTemplatesComponent extends PageComponent implements OnInit, HasConfirmForm {

  readonly templateKeys = mailTemplateKeys;

  settingsForm: FormGroup;
  subjectControl = new FormControl('');
  bodyControl = new FormControl('');

  selectedTemplate = mailTemplateKeys[0];

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
    this.mailTemplateService.getMailTemplateSettings().subscribe(settings => this.setSettings(settings));
  }

  confirmForm(): FormGroup {
    return this.settingsForm;
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

