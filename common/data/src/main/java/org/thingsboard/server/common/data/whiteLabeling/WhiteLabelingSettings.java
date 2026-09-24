// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.whiteLabeling;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Schema
@Data
public class WhiteLabelingSettings implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Whether custom branding is enabled. When disabled, the default branding is used.")
    private boolean enabled;

    @Schema(description = "Application title. Used in the browser tab, login page and navigation bar.")
    private String appTitle;

    @Schema(description = "Absolute URL or relative path of the logo displayed on light backgrounds (main UI).")
    private String logoImageUrl;

    @Schema(description = "Absolute URL or relative path of the logo displayed on dark backgrounds (login page).")
    private String logoImageUrlDark;

    @Schema(description = "Absolute URL or relative path of the favicon.")
    private String faviconUrl;

    @Schema(description = "Logo height in pixels. When set, overrides the default logo size.")
    private Integer logoHeight;

    @Schema(description = "Primary color in hex format, for example #305680. Applied as CSS variables when set.")
    private String primaryColor;

    @Schema(description = "Accent color in hex format, for example #ff5722. Applied as CSS variables when set.")
    private String accentColor;

    @Schema(description = "Background color in hex format, for example #f4f4f4. Applied to the application background.")
    private String backgroundColor;

    @Schema(description = "When enabled, hides documentation and help links that point to the default vendor documentation.")
    private boolean hideHelpLinks;

    @Schema(description = "When enabled, hides vendor promotion elements (GitHub badge, vendor home page promotion).")
    private boolean hideVendorPromotion;

    @Schema(description = "Custom CSS appended to the branding styles. Applied as-is, use with caution.")
    private String advancedCss;

    @Schema(description = "When enabled, hides the device connectivity dialog.")
    private boolean hideConnectivityDialog;

    @Schema(description = "When enabled, overrides the Trendz analytics add-on name.")
    private boolean overrideTrendzName;

    @Schema(description = "Custom name of the Trendz analytics add-on.")
    private String trendzName;

    @Schema(description = "When enabled, shows the platform name and version on the login page.")
    private boolean showPlatformNameVersion;

}
