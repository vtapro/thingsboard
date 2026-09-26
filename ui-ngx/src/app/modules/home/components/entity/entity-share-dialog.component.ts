// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { PageComponent } from '@shared/components/page.component';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { UserService } from '@core/http/user.service';
import { PageLink } from '@shared/models/page/page-link';
import { RbacShare, RbacShareService } from '@core/http/rbac-share.service';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';
import { forkJoin } from 'rxjs';

/** Operations granted by each level of the share (the backend stores the operations explicitly). */
const SHARE_LEVELS: { [level: string]: string[] } = {
  VIEW: ['READ', 'READ_ATTRIBUTES', 'READ_TELEMETRY'],
  CONTROL: ['READ', 'READ_ATTRIBUTES', 'READ_TELEMETRY', 'RPC_CALL', 'WRITE_TELEMETRY'],
  FULL: ['READ', 'READ_ATTRIBUTES', 'READ_TELEMETRY', 'RPC_CALL', 'WRITE_TELEMETRY', 'WRITE', 'DELETE',
    'WRITE_ATTRIBUTES', 'ASSIGN_TO_CUSTOMER']
};

interface ShareAssignee {
  type: 'USER' | 'USER_GROUP';
  id: string;
  name: string;
}

interface UserGroupInfo {
  id: string;
  name: string;
  userIds: string[];
}

interface TenantUserInfo {
  id: { id: string };
  email: string;
  firstName?: string;
  lastName?: string;
}

@Component({
  selector: 'tb-entity-share-dialog',
  templateUrl: './entity-share-dialog.component.html',
  styleUrls: ['./entity-share-dialog.component.scss'],
  standalone: false
})
export class EntityShareDialogComponent extends PageComponent implements OnInit {

  entityType: string;
  entityId: string;

  assignees: ShareAssignee[] = [];
  levels = ['NONE', 'VIEW', 'CONTROL', 'FULL'];
  levelByAssignee: { [key: string]: string } = {};
  loading = true;
  saving = false;

  constructor(protected store: Store<AppState>,
              private http: HttpClient,
              private userService: UserService,
              private shareService: RbacShareService,
              private translate: TranslateService,
              private dialogRef: MatDialogRef<EntityShareDialogComponent>,
              @Inject(MAT_DIALOG_DATA) data: { entityType: string; entityId: string }) {
    super(store);
    this.entityType = data.entityType;
    this.entityId = data.entityId;
  }

  ngOnInit(): void {
    forkJoin({
      users: this.userService.getUsers(new PageLink(100)),
      groups: this.http.get<{ groups: UserGroupInfo[] }>('/api/tenant/userGroup',
        defaultHttpOptionsFromConfig(undefined)),
      shares: this.shareService.getShares()
    }).subscribe(({users, groups, shares}) => {
      this.assignees = [
        ...(users?.data || []).map(user => ({
          type: 'USER' as const,
          id: user.id.id,
          name: this.userLabel(user)
        })),
        ...(groups?.groups || []).map(group => ({
          type: 'USER_GROUP' as const,
          id: group.id,
          name: `${group.name} (${this.translate.instant('share.user-group')})`
        }))
      ];
      const current = (shares?.shares || []).filter(share => share.entityType === this.entityType
        && share.entityId === this.entityId);
      this.assignees.forEach(assignee => {
        const share = current.find(item => item.assigneeType === assignee.type && item.assigneeId === assignee.id);
        this.levelByAssignee[this.key(assignee)] = share ? this.levelOf(share.operations) : 'NONE';
      });
      this.loading = false;
    });
  }

  key(assignee: ShareAssignee): string {
    return `${assignee.type}:${assignee.id}`;
  }

  setLevel(assignee: ShareAssignee, level: string): void {
    this.levelByAssignee[this.key(assignee)] = level;
  }

  /** Highest level fully granted by the share, or VIEW for a partial grant. */
  private levelOf(operations: string[]): string {
    if (!operations || !operations.length) {
      return 'NONE';
    }
    for (const level of ['FULL', 'CONTROL', 'VIEW']) {
      if (SHARE_LEVELS[level].every(operation => operations.includes(operation))) {
        return level;
      }
    }
    return 'VIEW';
  }

  save(): void {
    this.saving = true;
    this.shareService.getShares().subscribe(current => {
      // the settings object is saved as a whole: the shares of the other entities must be preserved
      const shares: RbacShare[] = (current?.shares || [])
        .filter(share => !(share.entityType === this.entityType && share.entityId === this.entityId));
      this.assignees.forEach(assignee => {
        const level = this.levelByAssignee[this.key(assignee)];
        if (level && level !== 'NONE') {
          shares.push({
            entityType: this.entityType,
            entityId: this.entityId,
            assigneeType: assignee.type,
            assigneeId: assignee.id,
            operations: SHARE_LEVELS[level]
          });
        }
      });
      this.shareService.saveShares({shares}).subscribe({
        next: () => {
          this.store.dispatch(new ActionNotificationShow({
            message: this.translate.instant('share.save-success'),
            type: 'success'
          }));
          this.dialogRef.close(true);
        },
        error: (error: HttpErrorResponse) => {
          this.saving = false;
          const details = error?.error?.message;
          this.store.dispatch(new ActionNotificationShow({
            message: details ? `${this.translate.instant('share.save-failed')}: ${details}`
              : this.translate.instant('share.save-failed'),
            type: 'error',
            duration: 5000
          }));
        }
      });
    }, () => this.saving = false);
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  private userLabel(user: { email?: string; firstName?: string; lastName?: string }): string {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ');
    return name ? `${name} (${user.email})` : user.email;
  }

}
