// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { EmulatorService } from '@core/http/emulator.service';
import {
  EmulatorCatalogData,
  EmulatorInstance,
  EmulatorProfile,
  EmulatorStatus
} from '@shared/models/emulator.models';
import {
  EmulatorCreateDialogComponent,
  EmulatorCreateDialogData
} from '@home/pages/admin/emulator-create-dialog.component';
import { Router } from '@angular/router';
import { PageEvent } from '@angular/material/paginator';

@Component({
  selector: 'tb-emulator',
  templateUrl: './emulator.component.html',
  styleUrls: ['./emulator.component.scss'],
  standalone: false
})
export class EmulatorComponent extends PageComponent implements OnInit {

  readonly emulatorStatus = EmulatorStatus;
  readonly intervals = [5, 10, 30, 60, 300];
  readonly pageSizeOptions = [10, 20, 50];

  catalog: EmulatorCatalogData = {categories: [], types: [], profiles: []};
  profiles: EmulatorProfile[] = [];
  emulators: EmulatorInstance[] = [];

  isLoading = true;
  loadingEmulatorId: string;
  pageIndex = 0;
  pageSize = 10;
  selectedTab = 0;

  categoryFilter = '';
  typeFilter = '';
  catalogSearch = '';
  verifiedOnly = false;

  statusFilter: EmulatorStatus | '' = '';
  profileFilter = '';
  emulatorSearch = '';

  constructor(protected store: Store<AppState>,
              private emulatorService: EmulatorService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private snackBar: MatSnackBar,
              private translate: TranslateService,
              private router: Router) {
    super(store);
  }

  ngOnInit(): void {
    this.loadCatalog();
    this.loadEmulators();
  }

  loadCatalog(): void {
    this.emulatorService.getCatalog().subscribe({
      next: (catalog) => {
        this.catalog = catalog;
        this.applyCatalogFilters();
      },
      error: () => this.showMessage('emulator.load-error', 'error')
    });
  }

  loadEmulators(): void {
    this.isLoading = true;
    this.emulatorService.getEmulators().subscribe({
      next: (emulators) => {
        this.emulators = emulators;
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
        this.showMessage('emulator.load-error', 'error');
      }
    });
  }

  applyCatalogFilters(): void {
    const search = this.catalogSearch.trim().toLowerCase();
    this.profiles = this.catalog.profiles.filter(profile => {
      if (this.categoryFilter && profile.category !== this.categoryFilter) {
        return false;
      }
      if (this.typeFilter && profile.type !== this.typeFilter) {
        return false;
      }
      if (this.verifiedOnly && !profile.verified) {
        return false;
      }
      if (!search) {
        return true;
      }
      const haystack = [profile.name, profile.category, profile.type, profile.description,
        ...profile.signals.map(signal => signal.key)]
        .join(' ').toLowerCase();
      return haystack.includes(search);
    });
  }

  get verifiedCount(): number {
    return this.catalog.profiles.filter(profile => profile.verified).length;
  }

  get filteredEmulators(): EmulatorInstance[] {
    const search = this.emulatorSearch.trim().toLowerCase();
    return this.emulators.filter(emulator => {
      if (this.statusFilter && emulator.status !== this.statusFilter) {
        return false;
      }
      if (this.profileFilter && emulator.profileId !== this.profileFilter) {
        return false;
      }
      if (!search) {
        return true;
      }
      return (emulator.name + ' ' + emulator.profileName + ' ' + emulator.deviceName)
        .toLowerCase().includes(search);
    });
  }

  get pagedEmulators(): EmulatorInstance[] {
    const from = this.pageIndex * this.pageSize;
    return this.filteredEmulators.slice(from, from + this.pageSize);
  }

  onPageChange(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
  }

  emulatorCount(profileId: string): number {
    return this.emulators.filter(emulator => emulator.profileId === profileId).length;
  }

  stoppedCount(profileId: string): number {
    return this.emulators.filter(emulator => emulator.profileId === profileId
      && emulator.status === EmulatorStatus.STOPPED).length;
  }

  countByStatus(status: EmulatorStatus): number {
    return this.emulators.filter(emulator => emulator.status === status).length;
  }

  createEmulator(profile: EmulatorProfile): void {
    this.dialog.open<EmulatorCreateDialogComponent, EmulatorCreateDialogData, EmulatorInstance>(
      EmulatorCreateDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {profile}
      }
    ).afterClosed().subscribe((created) => {
      if (created) {
        this.showMessage('emulator.created', 'success');
        this.loadEmulators();
      }
    });
  }

  setStatus(emulator: EmulatorInstance, status: EmulatorStatus, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.loadingEmulatorId = emulator.id;
    this.emulatorService.setStatus(emulator.id, status).subscribe({
      next: () => {
        this.loadingEmulatorId = null;
        this.loadEmulators();
      },
      error: () => {
        this.loadingEmulatorId = null;
        this.showMessage('emulator.save-error', 'error');
      }
    });
  }

  changeScenario(emulator: EmulatorInstance, scenario: string): void {
    this.emulatorService.updateEmulator(emulator.id, scenario, emulator.intervalSeconds).subscribe({
      next: (updated) => {
        emulator.scenario = updated.scenario;
        this.showMessage('emulator.scenario-changed', 'success');
      },
      error: () => this.showMessage('emulator.save-error', 'error')
    });
  }

  changeInterval(emulator: EmulatorInstance, intervalSeconds: number): void {
    this.emulatorService.updateEmulator(emulator.id, emulator.scenario, intervalSeconds).subscribe({
      next: (updated) => {
        emulator.intervalSeconds = updated.intervalSeconds;
        this.showMessage('emulator.interval-changed', 'success');
      },
      error: () => this.showMessage('emulator.save-error', 'error')
    });
  }

  generateHistory(emulator: EmulatorInstance, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.loadingEmulatorId = emulator.id;
    this.emulatorService.generateHistory(emulator.id, 24).subscribe({
      next: (result) => {
        this.loadingEmulatorId = null;
        this.showMessage('emulator.history-generated', 'success', result.published);
      },
      error: () => {
        this.loadingEmulatorId = null;
        this.showMessage('emulator.save-error', 'error');
      }
    });
  }

  createDashboard(emulator: EmulatorInstance, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.loadingEmulatorId = emulator.id;
    this.emulatorService.createDashboard(emulator.id).subscribe({
      next: () => {
        this.loadingEmulatorId = null;
        this.showMessage('emulator.dashboard-created', 'success');
        this.loadEmulators();
      },
      error: () => {
        this.loadingEmulatorId = null;
        this.showMessage('emulator.save-error', 'error');
      }
    });
  }

  /**
   * Removes the emulator records whose device was deleted (the "Clear Unlinked" action of PE).
   */
  clearUnlinked($event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.emulatorService.clearUnlinked().subscribe({
      next: (result) => {
        this.showMessage('emulator.clear-unlinked-done', 'success', result.cleared);
        this.loadEmulators();
      },
      error: () => this.showMessage('emulator.save-error', 'error')
    });
  }

  openDashboard(emulator: EmulatorInstance, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    if (!emulator.dashboardId) {
      this.createDashboard(emulator);
      return;
    }
    this.router.navigate(['/dashboards', emulator.dashboardId]);
  }

  openDevice(emulator: EmulatorInstance, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    if (emulator.deviceId?.id) {
      this.router.navigate(['/entities/devices', emulator.deviceId.id]);
    }
  }

  removeEmulator(emulator: EmulatorInstance, $event?: Event): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.dialogService.confirm(
      this.translate.instant('emulator.delete-title', {name: emulator.name}),
      this.translate.instant('emulator.delete-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe((result) => {
      if (result) {
        this.emulatorService.deleteEmulator(emulator.id).subscribe({
          next: () => {
            this.showMessage('emulator.deleted', 'success');
            this.loadEmulators();
          },
          error: () => this.showMessage('emulator.save-error', 'error')
        });
      }
    });
  }

  statusLabel(emulator: EmulatorInstance): string {
    return this.translate.instant('emulator.status-' + emulator.status.toLowerCase());
  }

  lastActivity(emulator: EmulatorInstance): string {
    if (!emulator.lastActivityTs) {
      return this.translate.instant('emulator.never');
    }
    const seconds = Math.max(0, Math.round((Date.now() - emulator.lastActivityTs) / 1000));
    if (seconds < 60) {
      return this.translate.instant('emulator.seconds-ago', {seconds});
    }
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) {
      return this.translate.instant('emulator.minutes-ago', {minutes});
    }
    return this.translate.instant('emulator.hours-ago', {hours: Math.round(minutes / 60)});
  }

  intervalLabel(seconds: number): string {
    if (seconds < 60) {
      return this.translate.instant('emulator.interval-seconds', {seconds});
    }
    return this.translate.instant('emulator.interval-minutes', {minutes: Math.round(seconds / 60)});
  }

  scenarioList(emulator: EmulatorInstance): string[] {
    const profile = this.catalog.profiles.find(item => item.id === emulator.profileId);
    return profile ? profile.scenarios : [emulator.scenario];
  }

  private showMessage(key: string, type: 'success' | 'error', value?: number): void {
    const message = value !== undefined
      ? this.translate.instant(key, {value})
      : this.translate.instant(key);
    this.snackBar.open(message, null, {duration: 3000, panelClass: type === 'error' ? 'tb-error' : 'tb-success'});
  }

}
