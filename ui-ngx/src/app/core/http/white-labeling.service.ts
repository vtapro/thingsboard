// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, Observable, of, ReplaySubject } from 'rxjs';
import { catchError, map, shareReplay, tap } from 'rxjs/operators';

import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { ImageService } from '@core/http/image.service';
import {
  defaultWhiteLabelingSettings,
  WhiteLabelingSettings
} from '@shared/models/white-labeling.models';
import { MenuId, setMenuSectionLabel } from '@core/services/menu.models';

@Injectable({
  providedIn: 'root'
})
export class WhiteLabelingService {

  private static readonly STYLE_ID = 'tb-white-labeling-theme';

  private readonly settingsSubject = new ReplaySubject<WhiteLabelingSettings>(1);

  private request$: Observable<WhiteLabelingSettings>;

  private currentSettings: WhiteLabelingSettings = {...defaultWhiteLabelingSettings};

  public readonly settings$: Observable<WhiteLabelingSettings> = this.settingsSubject.asObservable();

  /**
   * Last applied settings, allows synchronous checks (menu, entity actions) without an extra subscription.
   */
  public get settings(): WhiteLabelingSettings {
    return this.currentSettings;
  }

  constructor(private http: HttpClient,
              private imageService: ImageService,
              @Inject(DOCUMENT) private document: Document) {}

  public loadWhiteLabelingSettings(force = false): Observable<WhiteLabelingSettings> {
    if (!this.request$ || force) {
      this.request$ = this.http.get<WhiteLabelingSettings>('/api/noauth/whiteLabeling',
        defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true})).pipe(
        map(settings => this.withDefaults(settings)),
        catchError(() => of({...defaultWhiteLabelingSettings})),
        tap(settings => this.publish(settings)),
        shareReplay(1)
      );
    }
    return this.request$;
  }

  public getAdminWhiteLabelingSettings(config?: RequestConfig): Observable<WhiteLabelingSettings> {
    return this.http.get<WhiteLabelingSettings>('/api/admin/whiteLabeling',
      defaultHttpOptionsFromConfig(config)).pipe(
      map(settings => this.withDefaults(settings))
    );
  }

  public saveWhiteLabelingSettings(settings: WhiteLabelingSettings,
                                   config?: RequestConfig): Observable<WhiteLabelingSettings> {
    return this.http.post<WhiteLabelingSettings>('/api/admin/whiteLabeling', settings,
      defaultHttpOptionsFromConfig(config)).pipe(
      map(savedSettings => this.withDefaults(savedSettings)),
      tap(savedSettings => this.publish(savedSettings))
    );
  }

  public getTenantWhiteLabelingSettings(config?: RequestConfig): Observable<WhiteLabelingSettings> {
    return this.http.get<WhiteLabelingSettings>('/api/whiteLabeling',
      defaultHttpOptionsFromConfig(config)).pipe(
      map(settings => this.withDefaults(settings))
    );
  }

  public saveTenantWhiteLabelingSettings(settings: WhiteLabelingSettings,
                                         config?: RequestConfig): Observable<WhiteLabelingSettings> {
    return this.http.post<WhiteLabelingSettings>('/api/tenant/whiteLabeling', settings,
      defaultHttpOptionsFromConfig(config)).pipe(
      map(savedSettings => this.withDefaults(savedSettings)),
      tap(savedSettings => this.publish(savedSettings))
    );
  }

  public loadAuthenticatedWhiteLabelingSettings(): void {
    this.http.get<WhiteLabelingSettings>('/api/whiteLabeling',
      defaultHttpOptionsFromConfig({ignoreLoading: true, ignoreErrors: true})).pipe(
      catchError(() => of(null))
    ).subscribe(settings => {
      if (settings) {
        this.publish(this.withDefaults(settings));
      }
    });
  }

  public previewWhiteLabelingSettings(settings: WhiteLabelingSettings): void {
    this.publish(this.withDefaults(settings));
  }

  private publish(settings: WhiteLabelingSettings) {
    forkJoin({
      logoImageUrl: this.resolveImage(settings.logoImageUrl),
      logoImageUrlDark: this.resolveImage(settings.logoImageUrlDark),
      faviconUrl: this.resolveImage(settings.faviconUrl)
    }).subscribe(images => {
      const displaySettings: WhiteLabelingSettings = {
        ...settings,
        logoImageUrl: images.logoImageUrl,
        logoImageUrlDark: images.logoImageUrlDark,
        faviconUrl: images.faviconUrl
      };
      this.currentSettings = displaySettings;
      this.settingsSubject.next(displaySettings);
      this.applyTheme(displaySettings);
      this.applyTrendzName(displaySettings);
    });
  }

  private applyTrendzName(settings: WhiteLabelingSettings) {
    setMenuSectionLabel(MenuId.trendz_settings,
      settings.enabled && settings.overrideTrendzName && settings.trendzName ? settings.trendzName : null);
  }

  private resolveImage(url: string): Observable<string> {
    if (!url) {
      return of(null);
    }
    return this.imageService.resolveImageUrl(url, false, true, '').pipe(
      map(resolvedUrl => resolvedUrl as string),
      catchError(() => of(null))
    );
  }

  private withDefaults(settings: WhiteLabelingSettings): WhiteLabelingSettings {
    return {...defaultWhiteLabelingSettings, ...settings};
  }

  private applyTheme(settings: WhiteLabelingSettings) {
    let style: HTMLStyleElement = this.document.getElementById(WhiteLabelingService.STYLE_ID) as HTMLStyleElement;
    const css = this.buildThemeCss(settings);
    if (!css) {
      if (style) {
        style.remove();
      }
      return;
    }
    if (!style) {
      style = this.document.createElement('style');
      style.id = WhiteLabelingService.STYLE_ID;
      this.document.head.appendChild(style);
    }
    style.textContent = css;
  }

  private buildThemeCss(settings: WhiteLabelingSettings): string {
    if (!settings.enabled) {
      return null;
    }
    const rules: string[] = [];
    const primary = this.normalizeColor(settings.primaryColor);
    const accent = this.normalizeColor(settings.accentColor);
    let accentCss = '';
    if (primary) {
      const onPrimary = this.contrastColor(primary);
      rules.push(
        `--mat-sys-primary: ${primary};`,
        `--mat-sys-on-primary: ${onPrimary};`,
        `--mdc-filled-button-container-color: ${primary};`,
        `--mdc-filled-button-label-text-color: ${onPrimary};`,
        `--mdc-protected-button-container-color: ${primary};`,
        `--mdc-protected-button-label-text-color: ${onPrimary};`,
        `--mdc-text-button-label-text-color: ${primary};`,
        `--mdc-outlined-button-label-text-color: ${primary};`,
        `--mdc-outlined-button-outline-color: ${primary};`,
        `--mdc-icon-button-icon-color: ${primary};`,
        `--mdc-checkbox-selected-icon-color: ${primary};`,
        `--mdc-checkbox-selected-focus-icon-color: ${primary};`,
        `--mdc-checkbox-selected-hover-icon-color: ${primary};`,
        `--mdc-checkbox-selected-pressed-icon-color: ${primary};`,
        `--mdc-radio-selected-icon-color: ${primary};`,
        `--mdc-radio-selected-focus-icon-color: ${primary};`,
        `--mdc-radio-selected-hover-icon-color: ${primary};`,
        `--mdc-switch-selected-handle-color: ${primary};`,
        `--mdc-switch-selected-track-color: ${primary};`,
        `--mdc-switch-selected-hover-track-color: ${primary};`,
        `--mdc-switch-selected-focus-track-color: ${primary};`,
        `--mdc-switch-selected-pressed-track-color: ${primary};`,
        `--mdc-slider-handle-color: ${primary};`,
        `--mdc-slider-focus-handle-color: ${primary};`,
        `--mdc-slider-hover-handle-color: ${primary};`,
        `--mdc-slider-active-track-color: ${primary};`,
        `--mdc-linear-progress-active-indicator-color: ${primary};`
      );
      rules.push(
        `--mat-sidenav-container-background-color: ${primary};`,
        `--mat-sidenav-container-text-color: ${onPrimary};`
      );
      accentCss += `.tb-site-sidenav, .tb-side-menu-toolbar { background-color: ${primary} !important; color: ${onPrimary}; }` +
        ` .tb-site-sidenav .tb-nav-header-toolbar, .tb-side-menu-toolbar { background-color: transparent !important; }` +
        ` .tb-site-sidenav a, .tb-site-sidenav button, .tb-site-sidenav .mat-icon, .tb-site-sidenav tb-icon,` +
        ` .tb-side-menu-toolbar a, .tb-side-menu-toolbar button, .tb-side-menu-toolbar .mat-icon, .tb-side-menu-toolbar tb-icon` +
        ` { color: ${onPrimary}; }` +
        ` .tb-site-sidenav a.tb-active, .tb-side-menu-toolbar a.tb-active { background-color: rgba(255, 255, 255, 0.18) !important; }`;
    }
    if (accent) {
      const onAccent = this.contrastColor(accent);
      rules.push(
        `--mat-sys-tertiary: ${accent};`,
        `--mat-sys-on-tertiary: ${onAccent};`
      );
      accentCss = `.mat-accent, .mat-tertiary, .tb-accent {` +
        ` --mdc-filled-button-container-color: ${accent};` +
        ` --mdc-filled-button-label-text-color: ${onAccent};` +
        ` --mdc-protected-button-container-color: ${accent};` +
        ` --mdc-protected-button-label-text-color: ${onAccent};` +
        ` --mdc-text-button-label-text-color: ${accent};` +
        ` --mdc-outlined-button-label-text-color: ${accent};` +
        ` --mdc-outlined-button-outline-color: ${accent};` +
        ` --mdc-icon-button-icon-color: ${accent};` +
        ` --mdc-fab-container-color: ${accent};` +
        ` --mdc-fab-foreground-color: ${onAccent};` +
        ` --mdc-checkbox-selected-icon-color: ${accent};` +
        ` --mdc-radio-selected-icon-color: ${accent};` +
        ` --mdc-switch-selected-track-color: ${accent};` +
        ` --mdc-slider-handle-color: ${accent};` +
        ` --mdc-slider-active-track-color: ${accent};` +
        ' }';
    }
    const background = this.normalizeColor(settings.backgroundColor);
    let backgroundCss = '';
    if (background) {
      // 'html, body' is deliberately NOT styled here: it is the colour of the boot screen shown
      // while Angular is still loading (see src/index.html, which keeps it white). Painting it with
      // the configured background made a fresh page load flash in the tenant colour.
      // ':not(.tb-booting)' keeps the boot screen white as well - the class is dropped by
      // AppComponent.onActivateComponent as soon as the first routed page is rendered.
      backgroundCss = ` .tb-default:not(.tb-booting), .tb-dark:not(.tb-booting) { --mat-sys-surface: ${background};` +
        ` --mat-sys-background: ${background};` +
        ` --mat-app-background-color: ${background}; background-color: ${background}; }`;
    }
    if (!rules.length) {
      return [backgroundCss, accentCss, settings.advancedCss].filter(part => !!part).join('\n') || null;
    }
    const themeCss = `.tb-default, .tb-dark { ${rules.join(' ')} }`;
    return [themeCss, accentCss, backgroundCss, settings.advancedCss].filter(part => !!part).join('\n');
  }

  private normalizeColor(color: string): string {
    const value = (color || '').trim();
    if (/^#[0-9a-fA-F]{6}$/.test(value)) {
      return value;
    }
    if (/^#[0-9a-fA-F]{3}$/.test(value)) {
      return `#${value.charAt(1)}${value.charAt(1)}${value.charAt(2)}${value.charAt(2)}${value.charAt(3)}${value.charAt(3)}`;
    }
    return null;
  }

  private contrastColor(hexColor: string): string {
    const red = parseInt(hexColor.substring(1, 3), 16);
    const green = parseInt(hexColor.substring(3, 5), 16);
    const blue = parseInt(hexColor.substring(5, 7), 16);
    const luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
    return luminance > 0.6 ? 'rgba(0, 0, 0, 0.87)' : '#ffffff';
  }

}
