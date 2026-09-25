// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { Observable } from 'rxjs';
import { AutomationRule, AutomationRules } from '@shared/models/automation.models';

@Injectable({
  providedIn: 'root'
})
export class AutomationService {

  constructor(private http: HttpClient) {
  }

  public getAutomationRules(config?: RequestConfig): Observable<AutomationRules> {
    return this.http.get<AutomationRules>('/api/tenant/automation', defaultHttpOptionsFromConfig(config));
  }

  public saveAutomationRule(rule: AutomationRule, config?: RequestConfig): Observable<AutomationRule> {
    return this.http.post<AutomationRule>('/api/tenant/automation', rule, defaultHttpOptionsFromConfig(config));
  }

  public deleteAutomationRule(ruleId: string, config?: RequestConfig): Observable<void> {
    return this.http.delete<void>(`/api/tenant/automation/${ruleId}`, defaultHttpOptionsFromConfig(config));
  }

  public setAutomationRuleEnabled(ruleId: string, enabled: boolean, config?: RequestConfig): Observable<AutomationRule> {
    return this.http.post<AutomationRule>(`/api/tenant/automation/${ruleId}/enabled/${enabled}`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public runAutomationRuleNow(ruleId: string, config?: RequestConfig): Observable<AutomationRule> {
    return this.http.post<AutomationRule>(`/api/tenant/automation/${ruleId}/run`, null,
      defaultHttpOptionsFromConfig(config));
  }
}
