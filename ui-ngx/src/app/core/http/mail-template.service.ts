// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { MailTemplateSettings } from '@shared/models/mail-template.models';

@Injectable({
  providedIn: 'root'
})
export class MailTemplateService {

  constructor(private http: HttpClient) {}

  public getMailTemplateSettings(config?: RequestConfig): Observable<MailTemplateSettings> {
    return this.http.get<MailTemplateSettings>('/api/tenant/mailTemplates', defaultHttpOptionsFromConfig(config));
  }

  public saveMailTemplateSettings(settings: MailTemplateSettings, config?: RequestConfig): Observable<MailTemplateSettings> {
    return this.http.post<MailTemplateSettings>('/api/tenant/mailTemplates', settings, defaultHttpOptionsFromConfig(config));
  }

}

