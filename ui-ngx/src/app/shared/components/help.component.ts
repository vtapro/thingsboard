// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input, OnInit } from '@angular/core';
import { HelpLinks } from '@shared/models/constants';
import { WhiteLabelingService } from '@core/http/white-labeling.service';

@Component({
    selector: '[tb-help]',
    templateUrl: './help.component.html',
    standalone: false
})
export class HelpComponent implements OnInit {

  @Input('tb-help') helpLinkId: string;

  hidden = false;

  constructor(private whiteLabelingService: WhiteLabelingService) {
  }

  ngOnInit(): void {
    this.whiteLabelingService.settings$.subscribe(settings => {
      this.hidden = settings.enabled && settings.hideHelpLinks;
    });
  }

  gotoHelpPage(): void {
    let helpUrl = HelpLinks.linksMap[this.helpLinkId];
    if (!helpUrl && this.helpLinkId &&
      (this.helpLinkId.startsWith('http://') || this.helpLinkId.startsWith('https://'))) {
      helpUrl = this.helpLinkId;
    }
    if (helpUrl) {
      window.open(helpUrl, '_blank');
    }
  }

}
