// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';

import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { CustomMenuItem, CustomMenuSettings } from '@shared/models/custom-menu.models';
import { setCustomMenuItems } from '@core/services/menu.models';

@Injectable({
  providedIn: 'root'
})
export class CustomMenuService {

  private cachedItems: CustomMenuItem[] = [];

  constructor(private http: HttpClient) {}

  public getCustomMenuSettings(config?: RequestConfig): Observable<CustomMenuSettings> {
    return this.http.get<CustomMenuSettings>('/api/tenant/customMenu', defaultHttpOptionsFromConfig(config)).pipe(
      tap(settings => this.cachedItems = settings?.items || [])
    );
  }

  public saveCustomMenuSettings(settings: CustomMenuSettings, config?: RequestConfig): Observable<CustomMenuSettings> {
    return this.http.post<CustomMenuSettings>('/api/tenant/customMenu', settings, defaultHttpOptionsFromConfig(config)).pipe(
      tap(saved => this.cachedItems = saved?.items || [])
    );
  }

  public loadCustomMenu(): void {
    this.http.get<CustomMenuSettings>('/api/customMenu',
      defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true})).subscribe(settings => {
      this.cachedItems = settings?.items || [];
      setCustomMenuItems(this.cachedItems);
    });
  }

  public getCustomMenuItems(authority?: string): CustomMenuItem[] {
    return (this.cachedItems || [])
      .filter(item => !item.assigneeType || item.assigneeType === authority)
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  }

}
