import { Component, InjectionToken, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { IntegrationProfile } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';

type ProfileListState = 'loading' | 'ready' | 'empty' | 'session-expired' | 'unavailable';

export interface BrowserWindow {
  location: Pick<Location, 'assign'>;
}

export const WINDOW = new InjectionToken<BrowserWindow>('WINDOW', {
  factory: () => window,
});

@Component({
  selector: 'app-integration-profile-list',
  standalone: true,
  templateUrl: './integration-profile-list.component.html',
  styleUrl: './integration-profile-list.component.css',
})
export class IntegrationProfileListComponent implements OnInit {
  private readonly profileService = inject(IntegrationProfileService);
  private readonly browserWindow = inject(WINDOW);

  readonly profiles = signal<IntegrationProfile[]>([]);
  readonly state = signal<ProfileListState>('loading');

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
}
