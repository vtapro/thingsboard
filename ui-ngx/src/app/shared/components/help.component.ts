// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, DestroyRef, inject, Input, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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

  private readonly destroyRef = inject(DestroyRef);

  constructor(private whiteLabelingService: WhiteLabelingService) {
  }

  ngOnInit(): void {
    this.whiteLabelingService.settings$.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(settings => {
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
