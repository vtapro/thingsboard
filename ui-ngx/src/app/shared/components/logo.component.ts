// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input, OnInit } from '@angular/core';
import { AuthService } from '@core/auth/auth.service';
import { AppState } from '@core/core.state';
import { Store } from '@ngrx/store';
import { UrlTree } from '@angular/router';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { UrlHolder } from '@shared/pipe/image.pipe';
import { WhiteLabelingService } from '@core/http/white-labeling.service';
import { WhiteLabelingSettings } from '@shared/models/white-labeling.models';

@Component({
    selector: 'tb-logo',
    templateUrl: './logo.component.html',
    styleUrls: ['./logo.component.scss'],
    standalone: false
})
export class LogoComponent implements OnInit {

  private static readonly DEFAULT_LOGO = 'assets/logo_title_white.svg';

  @Input()
  src: string | UrlHolder;

  @Input()
  darkBackground = false;

  @Input()
  link: string | UrlTree;

  @Input()
  target: string = null;

  private customLogoSrc: string;

  logoHeightPx: number;

  isExternal = false;

  constructor(private authService: AuthService,
              private store: Store<AppState>,
              private whiteLabelingService: WhiteLabelingService) {
  }

  get logoSrc(): string | UrlHolder {
    return this.src || this.customLogoSrc || LogoComponent.DEFAULT_LOGO;
  }

  ngOnInit() {
    this.whiteLabelingService.settings$.subscribe(settings => {
      this.customLogoSrc = this.resolveCustomLogoSrc(settings);
      this.logoHeightPx = settings.enabled ? settings.logoHeight : null;
    });
    if (!this.link) {
      const authState = getCurrentAuthState(this.store);
      this.link = this.authService.defaultUrl(true, authState);
    }
    if (typeof this.link === 'string' && this.link.startsWith('http')) {
      this.isExternal = true;
    }
  }

  private resolveCustomLogoSrc(settings: WhiteLabelingSettings): string {
    if (!settings.enabled) {
      return null;
    }
    return this.darkBackground
      ? settings.logoImageUrlDark || settings.logoImageUrl
      : settings.logoImageUrl || settings.logoImageUrlDark;
  }
}
