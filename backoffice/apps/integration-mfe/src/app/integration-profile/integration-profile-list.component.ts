import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { IntegrationProfile } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';

type ProfileListState = 'loading' | 'ready' | 'empty' | 'session-expired' | 'unavailable';

@Component({
  selector: 'app-integration-profile-list',
  standalone: true,
  templateUrl: './integration-profile-list.component.html',
  styleUrl: './integration-profile-list.component.css',
})
export class IntegrationProfileListComponent implements OnInit {
  private readonly profileService = inject(IntegrationProfileService);

  readonly profiles = signal<IntegrationProfile[]>([]);
  readonly state = signal<ProfileListState>('loading');

  ngOnInit(): void {
    this.profileService.list().subscribe({
      next: (profiles) => {
        this.profiles.set(profiles);
        this.state.set(profiles.length === 0 ? 'empty' : 'ready');
      },
      error: (error: HttpErrorResponse) => {
        if (error.status === 401 || error.status === 403) {
          this.state.set('session-expired');
          window.location.assign('/auth/login');
          return;
        }

        this.state.set('unavailable');
      },
    });
  }
}
