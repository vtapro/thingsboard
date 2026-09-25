// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';

import { AdminRoutingModule } from './admin-routing.module';
import { SharedModule } from '@app/shared/shared.module';
import { MailServerComponent } from '@modules/home/pages/admin/mail-server.component';
import { GeneralSettingsComponent } from '@modules/home/pages/admin/general-settings.component';
import { SecuritySettingsComponent } from '@modules/home/pages/admin/security-settings.component';
import { HomeComponentsModule } from '@modules/home/components/home-components.module';
import { SmsProviderComponent } from '@home/pages/admin/sms-provider.component';
import { SendTestSmsDialogComponent } from '@home/pages/admin/send-test-sms-dialog.component';
import { HomeSettingsComponent } from '@home/pages/admin/home-settings.component';
import { ResourceTabsComponent } from '@home/pages/admin/resource/resource-tabs.component';
import { ResourcesTableHeaderComponent } from '@home/pages/admin/resource/resources-table-header.component';
import { QueueComponent } from '@home/pages/admin/queue/queue.component';
import { RepositoryAdminSettingsComponent } from '@home/pages/admin/repository-admin-settings.component';
import { AutoCommitAdminSettingsComponent } from '@home/pages/admin/auto-commit-admin-settings.component';
import { TwoFactorAuthSettingsComponent } from '@home/pages/admin/two-factor-auth-settings.component';
import { OAuth2Module } from '@home/pages/admin/oauth2/oauth2.module';
import { JsLibraryTableHeaderComponent } from '@home/pages/admin/resource/js-library-table-header.component';
import { JsResourceComponent } from '@home/pages/admin/resource/js-resource.component';
import { NgxFlowModule } from '@flowjs/ngx-flow';
import { TrendzSettingsComponent } from '@home/pages/admin/trendz-settings.component';
import { ResourceLibraryTabsComponent } from '@home/pages/admin/resource/resource-library-tabs.component';
import { WhiteLabelingComponent } from '@home/pages/admin/white-labeling.component';
import { AdvancedCssDialogComponent } from '@home/pages/admin/advanced-css-dialog.component';
import { MailTemplatesComponent } from '@home/pages/admin/mail-templates.component';
import { CustomTranslationComponent } from '@home/pages/admin/custom-translation.component';
import { CustomMenuComponent } from '@home/pages/admin/custom-menu.component';
import { LoginSettingsComponent } from '@home/pages/admin/login-settings.component';
import { RolesComponent } from '@home/pages/admin/roles.component';
import { AutomationComponent } from '@home/pages/admin/automation.component';
import { AutomationRuleDialogComponent } from '@home/pages/admin/automation-rule-dialog.component';

@NgModule({
  declarations:
    [
      GeneralSettingsComponent,
      MailServerComponent,
      SmsProviderComponent,
      SendTestSmsDialogComponent,
      SecuritySettingsComponent,
      HomeSettingsComponent,
      ResourceTabsComponent,
      ResourceLibraryTabsComponent,
      ResourcesTableHeaderComponent,
      JsResourceComponent,
      JsLibraryTableHeaderComponent,
      QueueComponent,
      RepositoryAdminSettingsComponent,
      AutoCommitAdminSettingsComponent,
      TwoFactorAuthSettingsComponent,
      TrendzSettingsComponent,
      WhiteLabelingComponent,
      AdvancedCssDialogComponent,
      MailTemplatesComponent,
      CustomTranslationComponent,
      CustomMenuComponent,
      LoginSettingsComponent,
      RolesComponent,
      AutomationComponent,
      AutomationRuleDialogComponent
    ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    AdminRoutingModule,
    OAuth2Module,
    NgxFlowModule
  ]
})
export class AdminModule { }
