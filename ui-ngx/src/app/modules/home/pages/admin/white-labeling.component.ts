// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';

import { AppState } from '@core/core.state';
import { WhiteLabelingService } from '@core/http/white-labeling.service';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { PageComponent } from '@shared/components/page.component';
import { WhiteLabelingSettings } from '@shared/models/white-labeling.models';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { MatDialog } from '@angular/material/dialog';
import { AdvancedCssDialogComponent } from '@modules/home/pages/admin/advanced-css-dialog.component';

@Component({
    selector: 'tb-white-labeling',
    templateUrl: './white-labeling.component.html',
    styleUrls: ['./white-labeling.component.scss', './settings-card.scss'],
    standalone: false
})
export class WhiteLabelingComponent extends PageComponent implements OnInit, HasConfirmForm {

  readonly palettes: Array<{name: string; color: string}> = [
    {name: 'LIGHT BLUE', color: '#2196f3'},
    {name: 'CYAN', color: '#00bcd4'},
    {name: 'TEAL', color: '#009688'},
    {name: 'GREEN', color: '#4caf50'},
    {name: 'LIGHT GREEN', color: '#8bc34a'},
    {name: 'LIME', color: '#cddc39'},
    {name: 'AMBER', color: '#ffc107'},
    {name: 'ORANGE', color: '#ff9800'},
    {name: 'DEEP ORANGE', color: '#ff5722'},
    {name: 'PURPLE', color: '#9c27b0'},
    {name: 'INDIGO', color: '#3f51b5'},
    {name: 'PINK', color: '#e91e63'},
    {name: 'BROWN', color: '#795548'},
    {name: 'BLUE GREY', color: '#607d8b'}
  ];

  whiteLabelingForm: FormGroup;

  primaryPalette = 'Custom';
  accentPalette = 'Custom';
  backgroundPalette = 'Custom';

  readonly customPalette = 'Custom';

  private readonly colorPattern = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

  constructor(protected store: Store<AppState>,
              private fb: FormBuilder,
              private whiteLabelingService: WhiteLabelingService,
              private dialog: MatDialog) {
    super();
  }

  ngOnInit() {
    this.whiteLabelingForm = this.fb.group({
      enabled: [false],
      appTitle: [null],
      logoImageUrl: [null],
      logoImageUrlDark: [null],
      faviconUrl: [null],
      logoHeight: [null],
      primaryColor: [null, [Validators.pattern(this.colorPattern)]],
      accentColor: [null, [Validators.pattern(this.colorPattern)]],
      backgroundColor: [null, [Validators.pattern(this.colorPattern)]],
      hideHelpLinks: [false],
      hideVendorPromotion: [false],
      advancedCss: [null],
      hideConnectivityDialog: [false],
      overrideTrendzName: [false],
      trendzName: [null],
      hideChatBot: [false],
      showPlatformNameVersion: [false]
    });

    const settingsRequest$ = this.isSysAdmin()
      ? this.whiteLabelingService.getAdminWhiteLabelingSettings()
      : this.whiteLabelingService.getTenantWhiteLabelingSettings();
    settingsRequest$.subscribe((settings) => this.setWhiteLabelingSettings(settings));
  }

  confirmForm(): FormGroup {
    return this.whiteLabelingForm;
  }

  setWhiteLabelingSettings(settings: WhiteLabelingSettings) {
    this.whiteLabelingForm.reset({
      enabled: settings?.enabled ?? false,
      appTitle: settings?.appTitle ?? null,
      logoImageUrl: settings?.logoImageUrl ?? null,
      logoImageUrlDark: settings?.logoImageUrlDark ?? null,
      faviconUrl: settings?.faviconUrl ?? null,
      logoHeight: settings?.logoHeight ?? null,
      primaryColor: settings?.primaryColor ?? null,
      accentColor: settings?.accentColor ?? null,
      backgroundColor: settings?.backgroundColor ?? null,
      hideHelpLinks: settings?.hideHelpLinks ?? false,
      hideVendorPromotion: settings?.hideVendorPromotion ?? false,
      advancedCss: settings?.advancedCss ?? null,
      hideConnectivityDialog: settings?.hideConnectivityDialog ?? false,
      overrideTrendzName: settings?.overrideTrendzName ?? false,
      trendzName: settings?.trendzName ?? null,
      hideChatBot: settings?.hideChatBot ?? false,
      showPlatformNameVersion: settings?.showPlatformNameVersion ?? false
    });
    this.primaryPalette = this.paletteNameByColor(settings?.primaryColor);
    this.accentPalette = this.paletteNameByColor(settings?.accentColor);
    this.backgroundPalette = this.paletteNameByColor(settings?.backgroundColor);
  }

  onPrimaryPalette(paletteName: string) {
    this.primaryPalette = paletteName;
    const palette = this.palettes.find(p => p.name === paletteName);
    if (palette) {
      this.whiteLabelingForm.get('primaryColor').setValue(palette.color);
      this.whiteLabelingForm.get('primaryColor').markAsDirty();
    }
  }

  onAccentPalette(paletteName: string) {
    this.accentPalette = paletteName;
    const palette = this.palettes.find(p => p.name === paletteName);
    if (palette) {
      this.whiteLabelingForm.get('accentColor').setValue(palette.color);
      this.whiteLabelingForm.get('accentColor').markAsDirty();
    }
  }

  onBackgroundPalette(paletteName: string) {
    this.backgroundPalette = paletteName;
    const palette = this.palettes.find(p => p.name === paletteName);
    if (palette) {
      this.whiteLabelingForm.get('backgroundColor').setValue(palette.color);
      this.whiteLabelingForm.get('backgroundColor').markAsDirty();
    }
  }

  private paletteNameByColor(color: string): string {
    const palette = this.palettes.find(p => p.color.toLowerCase() === (color || '').toLowerCase());
    return palette ? palette.name : this.customPalette;
  }

  openAdvancedCss() {
    this.dialog.open(AdvancedCssDialogComponent, {
      data: {advancedCss: this.whiteLabelingForm.get('advancedCss').value}
    }).afterClosed().subscribe((advancedCss: string) => {
      if (advancedCss !== undefined) {
        this.whiteLabelingForm.get('advancedCss').setValue(advancedCss);
        this.whiteLabelingForm.get('advancedCss').markAsDirty();
      }
    });
  }

  preview() {
    this.whiteLabelingService.previewWhiteLabelingSettings(this.whiteLabelingForm.value);
  }

  resetToDefault() {
    const settings: WhiteLabelingSettings = {enabled: false};
    const saveRequest$ = this.isSysAdmin()
      ? this.whiteLabelingService.saveWhiteLabelingSettings(settings)
      : this.whiteLabelingService.saveTenantWhiteLabelingSettings(settings);
    saveRequest$.subscribe((savedSettings) => this.setWhiteLabelingSettings(savedSettings));
  }

  save() {
    const settings: WhiteLabelingSettings = this.whiteLabelingForm.value;
    const saveRequest$ = this.isSysAdmin()
      ? this.whiteLabelingService.saveWhiteLabelingSettings(settings)
      : this.whiteLabelingService.saveTenantWhiteLabelingSettings(settings);
    saveRequest$.subscribe((savedSettings) => this.setWhiteLabelingSettings(savedSettings));
  }

  private isSysAdmin(): boolean {
    return getCurrentAuthState(this.store).authUser?.authority === Authority.SYS_ADMIN;
  }

}
