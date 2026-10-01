// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { Observable } from 'rxjs';
import {
  EmulatorCatalogData,
  EmulatorCreateRequest,
  EmulatorInstance,
  EmulatorStatus
} from '@shared/models/emulator.models';

@Injectable({
  providedIn: 'root'
})
export class EmulatorService {

  constructor(private http: HttpClient) {
  }

  public getCatalog(config?: RequestConfig): Observable<EmulatorCatalogData> {
    return this.http.get<EmulatorCatalogData>('/api/tenant/emulator/catalog', defaultHttpOptionsFromConfig(config));
  }

  public getEmulators(config?: RequestConfig): Observable<EmulatorInstance[]> {
    return this.http.get<EmulatorInstance[]>('/api/tenant/emulator', defaultHttpOptionsFromConfig(config));
  }

  public createEmulator(request: EmulatorCreateRequest, config?: RequestConfig): Observable<EmulatorInstance> {
    return this.http.post<EmulatorInstance>('/api/tenant/emulator', request, defaultHttpOptionsFromConfig(config));
  }

  public setStatus(emulatorId: string, status: EmulatorStatus, config?: RequestConfig): Observable<EmulatorInstance> {
    return this.http.post<EmulatorInstance>(`/api/tenant/emulator/${emulatorId}/${status}`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public updateEmulator(emulatorId: string, scenario?: string, intervalSeconds?: number,
                        config?: RequestConfig): Observable<EmulatorInstance> {
    let url = `/api/tenant/emulator/${emulatorId}/scenario?`;
    if (scenario) {
      url += `scenario=${encodeURIComponent(scenario)}&`;
    }
    if (intervalSeconds) {
      url += `intervalSeconds=${intervalSeconds}`;
    }
    return this.http.post<EmulatorInstance>(url, null, defaultHttpOptionsFromConfig(config));
  }

  public createDashboard(emulatorId: string, config?: RequestConfig): Observable<EmulatorInstance> {
    return this.http.post<EmulatorInstance>(`/api/tenant/emulator/${emulatorId}/dashboard`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public generateHistory(emulatorId: string, hours: number, config?: RequestConfig): Observable<{published: number}> {
    return this.http.post<{published: number}>(`/api/tenant/emulator/${emulatorId}/history?hours=${hours}`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public deleteEmulator(emulatorId: string, deleteDevice = true, config?: RequestConfig): Observable<void> {
    return this.http.delete<void>(`/api/tenant/emulator/${emulatorId}?deleteDevice=${deleteDevice}`,
      defaultHttpOptionsFromConfig(config));
  }

  /** Removes the emulators whose device was deleted (like the "Clear Unlinked" action of PE). */
  public clearUnlinked(config?: RequestConfig): Observable<{cleared: number}> {
    return this.http.post<{cleared: number}>('/api/tenant/emulator/clearUnlinked', null,
      defaultHttpOptionsFromConfig(config));
  }
}
