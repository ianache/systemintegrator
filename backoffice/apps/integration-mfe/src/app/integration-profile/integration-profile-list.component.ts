import { ChangeDetectionStrategy, Component, InjectionToken, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { IntegrationProfile, SyncDirection } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';

type ProfileListState = 'loading' | 'ready' | 'empty' | 'session-expired' | 'unavailable';
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
        if (error.status === 401 || error.status === 403) {
          this.state.set('session-expired');
          this.browserWindow.location.assign('/auth/login');
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

  statusBadgeClass(active: boolean): string {
    return 'badge ' + (active ? 'active' : 'inactive');
  }
}
