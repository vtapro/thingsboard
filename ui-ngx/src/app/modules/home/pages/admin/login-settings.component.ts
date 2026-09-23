// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import { Store } from '@ngrx/store';
import { WINDOW } from '@core/services/window.service';

import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { PageComponent } from '@shared/components/page.component';

interface DomainInfo {
  id: { id: string };
  name: string;
  createdTime?: number;
}

@Component({
    selector: 'tb-login-settings',
    templateUrl: './login-settings.component.html',
    styleUrls: ['./login-settings.component.scss', './settings-card.scss'],
    standalone: false
})
export class LoginSettingsComponent extends PageComponent implements OnInit {

  domainControl = new FormControl('');

  domains: DomainInfo[] = [];

  currentHost: string;

  readonly displayedColumns = ['name', 'createdTime', 'actions'];

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              @Inject(WINDOW) private window: Window) {
    super();
  }

  ngOnInit() {
    this.currentHost = this.window.location.host;
    if (getCurrentAuthState(this.store).authUser?.authority === Authority.TENANT_ADMIN) {
      this.load();
    }
  }

  load() {
    this.http.get<{data: DomainInfo[]}>('/api/tenant/domain', defaultHttpOptionsFromConfig(undefined))
      .subscribe(page => this.domains = page?.data || []);
  }

  addDomain() {
    const name = (this.domainControl.value || '').trim();
    if (!name) {
      return;
    }
    this.http.post<DomainInfo>('/api/tenant/domain', {name},
      defaultHttpOptionsFromConfig(undefined)).subscribe(() => {
      this.domainControl.setValue('');
      this.load();
    });
  }

  deleteDomain(domain: DomainInfo) {
    this.http.delete(`/api/tenant/domain/${domain.id.id}`,
      defaultHttpOptionsFromConfig(undefined)).subscribe(() => this.load());
  }

}

