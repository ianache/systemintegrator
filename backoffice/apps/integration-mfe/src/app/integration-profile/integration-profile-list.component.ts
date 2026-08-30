import { ChangeDetectionStrategy, Component, InjectionToken, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { IntegrationProfile, IntegrationProfileStatus, SyncDirection } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { IntegrationProfileWizardComponent } from './integration-profile-wizard.component';
import { IntegrationTabsComponent } from '../shared/integration-tabs.component';
import { TimeAgoPipe } from '../shared/time-ago.pipe';

type ProfileListState = 'loading' | 'ready' | 'empty' | 'session-expired' | 'forbidden' | 'unavailable';
type DirectionFilter = 'ALL' | SyncDirection;

export interface BrowserWindow {
  location: Pick<Location, 'assign'>;
}

export const WINDOW = new InjectionToken<BrowserWindow>('WINDOW', {
  factory: () => window,
});

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-list',
  standalone: true,
  imports: [IntegrationProfileWizardComponent, IntegrationTabsComponent, TimeAgoPipe],
  templateUrl: './integration-profile-list.component.html',
  styleUrl: './integration-profile-list.component.css',
})
export class IntegrationProfileListComponent implements OnInit {
  private readonly profileService = inject(IntegrationProfileService);
  private readonly browserWindow = inject(WINDOW);
  private readonly router = inject(Router);

  readonly profiles = signal<IntegrationProfile[]>([]);
  readonly state = signal<ProfileListState>('loading');
  readonly search = signal('');
  readonly directionFilter = signal<DirectionFilter>('ALL');

  readonly directions: DirectionFilter[] = ['ALL', 'INBOUND', 'OUTBOUND', 'BIDIRECTIONAL'];

  readonly filteredProfiles = computed(() => {
    const query = this.search().trim().toLowerCase();
    const direction = this.directionFilter();
    return this.profiles().filter((profile) => {
      if (direction !== 'ALL' && profile.syncDirection !== direction) return false;
      if (!query) return true;
      const haystack = [
        profile.businessDomain,
        profile.externalSource,
        profile.configuration?.connector ?? '',
        profile.configuration?.adapter ?? '',
      ]
        .join(' ')
        .toLowerCase();
      return haystack.includes(query);
    });
  });

  ngOnInit(): void {
    this.retry();
  }

  retry(): void {
    this.state.set('loading');
    this.profileService.list().subscribe({
      next: (profiles) => {
        this.profiles.set(profiles);
        this.state.set(profiles.length === 0 ? 'empty' : 'ready');
      },
      error: (error: HttpErrorResponse) => {
        if (error.status === 401) {
          this.state.set('session-expired');
          this.browserWindow.location.assign('/auth/login');
          return;
        }
        if (error.status === 403) {
          // A valid, authenticated session was rejected by the Gateway (e.g. the
          // JWT carries no tenant_id claim) — not the same as a logged-out user,
          // so this must not force a re-login redirect.
          this.state.set('forbidden');
          return;
        }
        this.state.set('unavailable');
      },
    });
  }

  onSearch(value: string): void {
    this.search.set(value);
  }

  setDirection(direction: DirectionFilter): void {
    this.directionFilter.set(direction);
  }

  open(profile: IntegrationProfile): void {
    this.router.navigate(['/integration/profiles', profile.id]);
  }

  directionBadgeClass(direction: SyncDirection): string {
    return 'badge dir-' + direction.toLowerCase();
  }

  statusBadgeClass(status: IntegrationProfileStatus): string {
    return 'badge ' + status.toLowerCase();
  }

  statusLabel(status: IntegrationProfileStatus): string {
    const labels: Record<IntegrationProfileStatus, string> = {
      ACTIVE: 'Activo',
      PAUSED: 'Pausado',
      DRAFT: 'Borrador',
      ERROR: 'Con error',
      DEGRADED: 'Degradado',
      INACTIVE: 'Inactivo',
    };
    return labels[status];
  }

  readonly wizardOpen = signal(false);

  openWizard(): void {
    this.wizardOpen.set(true);
  }

  onCreated(profile: IntegrationProfile): void {
    this.wizardOpen.set(false);
    this.router.navigate(['/integration/profiles', profile.id]);
  }
}
