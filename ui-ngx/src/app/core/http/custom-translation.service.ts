// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';

export type CustomTranslations = { [key: string]: string };

@Injectable({
  providedIn: 'root'
})
export class CustomTranslationService {

  constructor(private http: HttpClient,
              private translate: TranslateService) {
    this.translate.onLangChange.subscribe(event => {
      this.loadAndApplyCustomTranslations(event.lang);
    });
  }

  public getAllCustomTranslations(config?: RequestConfig): Observable<{ [locale: string]: CustomTranslations }> {
    return this.http.get<{ [locale: string]: CustomTranslations }>('/api/tenant/customTranslation',
      defaultHttpOptionsFromConfig(config));
  }

  public getCustomTranslations(locale: string, config?: RequestConfig): Observable<CustomTranslations> {
    return this.http.get<CustomTranslations>(`/api/customTranslation/${locale}`,
      defaultHttpOptionsFromConfig(config));
  }

  public saveCustomTranslations(locale: string, translations: CustomTranslations,
                                config?: RequestConfig): Observable<CustomTranslations> {
    return this.http.post<CustomTranslations>(`/api/tenant/customTranslation/${locale}`, translations,
      defaultHttpOptionsFromConfig(config));
  }

  public deleteCustomTranslations(locale: string, config?: RequestConfig): Observable<{ [locale: string]: CustomTranslations }> {
    return this.http.delete<{ [locale: string]: CustomTranslations }>(`/api/tenant/customTranslation/${locale}`,
      defaultHttpOptionsFromConfig(config));
  }

  public loadAndApplyCustomTranslations(locale: string): void {
    this.getCustomTranslations(locale, {ignoreLoading: true, ignoreErrors: true}).subscribe(translations => {
      if (translations && Object.keys(translations).length) {
        this.translate.setTranslation(locale, translations, true);
      }
    });
  }

}

