// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Title } from '@angular/platform-browser';
import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { filter } from 'rxjs/operators';

import { environment as env } from '@env/environment';
import { WhiteLabelingService } from '@core/http/white-labeling.service';
import { WhiteLabelingSettings } from '@shared/models/white-labeling.models';

@Injectable({
  providedIn: 'root'
})
export class TitleService {

  private appTitle = env.appTitle;
  private lastTranslatedTitle: string;

  constructor(
    private translate: TranslateService,
    private title: Title,
    private whiteLabelingService: WhiteLabelingService
  ) {
    this.whiteLabelingService.settings$.subscribe(settings => {
      this.appTitle = this.resolveAppTitle(settings);
      this.updateTitle();
    });
  }

  setTitle(
    snapshot: ActivatedRouteSnapshot,
    lazyTranslate?: TranslateService
  ) {
    let lastChild = snapshot;
    while (lastChild.children.length) {
      lastChild = lastChild.children[0];
    }
    const { title } = lastChild.data;
    const translate = lazyTranslate || this.translate;
    this.lastTranslatedTitle = null;
    if (title) {
      translate
        .get(title)
        .pipe(filter(translatedTitle => translatedTitle !== title))
        .subscribe(translatedTitle => {
          this.lastTranslatedTitle = translatedTitle;
          this.updateTitle();
        });
    } else {
      this.updateTitle();
    }
  }

  private updateTitle() {
    if (this.lastTranslatedTitle) {
      this.title.setTitle(`${this.appTitle} | ${this.lastTranslatedTitle}`);
    } else {
      this.title.setTitle(this.appTitle);
    }
  }

  private resolveAppTitle(settings: WhiteLabelingSettings): string {
    const customTitle = (settings.appTitle || '').trim();
    return settings.enabled && customTitle ? customTitle : env.appTitle;
  }
}
