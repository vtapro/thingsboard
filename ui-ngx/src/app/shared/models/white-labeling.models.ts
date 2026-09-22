// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0

export interface WhiteLabelingSettings {
  enabled: boolean;
  appTitle?: string;
  logoImageUrl?: string;
  logoImageUrlDark?: string;
  faviconUrl?: string;
  logoHeight?: number;
  primaryColor?: string;
  accentColor?: string;
  backgroundColor?: string;
  hideHelpLinks?: boolean;
  hideVendorPromotion?: boolean;
  advancedCss?: string;
  hideConnectivityDialog?: boolean;
  overrideTrendzName?: boolean;
  trendzName?: string;
  hideChatBot?: boolean;
  showPlatformNameVersion?: boolean;
}

export const defaultWhiteLabelingSettings: WhiteLabelingSettings = {
  enabled: false,
  hideHelpLinks: false,
  hideVendorPromotion: false
};
