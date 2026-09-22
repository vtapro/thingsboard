// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { CustomTranslationService } from '@core/http/custom-translation.service';
import { PageComponent } from '@shared/components/page.component';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';

@Component({
    selector: 'tb-custom-translation',
    templateUrl: './custom-translation.component.html',
    styleUrls: ['./custom-translation.component.scss', './settings-card.scss'],
    standalone: false
})
export class CustomTranslationComponent extends PageComponent implements OnInit {

  localeControl = new FormControl('en_US');
  translationsControl = new FormControl('');

  locales: string[] = [];

  parseError = false;

  constructor(protected store: Store<AppState>,
              private customTranslationService: CustomTranslationService) {
    super();
  }

  ngOnInit() {
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.loadLocales();
      this.load(this.localeControl.value);
    }
  }

  loadLocales() {
    this.customTranslationService.getAllCustomTranslations().subscribe(translations => {
      this.locales = Object.keys(translations || {}).sort();
    });
  }

  selectLocale(locale: string) {
    this.localeControl.setValue(locale);
    this.load(locale);
  }

  load(locale: string) {
    this.customTranslationService.getCustomTranslations(locale).subscribe(translations => {
      this.parseError = false;
      this.translationsControl.setValue(JSON.stringify(translations || {}, null, 2));
    });
  }

  reload() {
    this.loadLocales();
    this.load(this.localeControl.value);
  }

  save() {
    let translations: { [key: string]: string };
    try {
      translations = JSON.parse(this.translationsControl.value || '{}');
      this.parseError = false;
    } catch (e) {
      this.parseError = true;
      return;
    }
    this.customTranslationService.saveCustomTranslations(this.localeControl.value, translations).subscribe(saved => {
      this.translationsControl.setValue(JSON.stringify(saved || {}, null, 2));
      this.loadLocales();
    });
  }

  delete() {
    this.customTranslationService.deleteCustomTranslations(this.localeControl.value).subscribe(() => {
      this.translationsControl.setValue('{}');
      this.loadLocales();
    });
  }

}
