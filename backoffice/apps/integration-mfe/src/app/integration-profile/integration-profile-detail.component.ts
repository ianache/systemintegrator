import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { IntegrationProfile } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';

export type DetailTab = 'general' | 'conn' | 'map' | 'pol' | 'sync';
type DetailState = 'loading' | 'ready' | 'not-found' | 'unavailable';

const TABS: { id: DetailTab; label: string }[] = [
  { id: 'general', label: 'General' },
  { id: 'conn', label: 'Conectividad' },
  { id: 'map', label: 'Mapping & Transformation' },
  { id: 'pol', label: 'Políticas' },
  { id: 'sync', label: 'Sincronización' },
];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-detail',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './integration-profile-detail.component.html',
  styleUrl: './integration-profile-detail.component.css',
})
export class IntegrationProfileDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly profileService = inject(IntegrationProfileService);

  readonly tabs = TABS;
  readonly state = signal<DetailState>('loading');
  readonly profile = signal<IntegrationProfile | null>(null);
  readonly tab = signal<DetailTab>('general');

  private profileId = '';

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((query) => {
      const requested = query.get('tab') as DetailTab | null;
      if (requested && this.tabs.some((t) => t.id === requested)) {
        this.tab.set(requested);
      }
    });
    this.route.paramMap.subscribe((params) => {
      const id = params.get('profileId');
      if (!id) return;
      this.profileId = id;
      this.load(id);
    });
  }

  reload(): void {
    if (this.profileId) this.load(this.profileId);
  }

  setTab(tab: DetailTab): void {
    this.tab.set(tab);
    this.router.navigate([], { relativeTo: this.route, queryParams: { tab }, queryParamsHandling: 'merge' });
  }

  private load(id: string): void {
    this.state.set('loading');
    this.profileService.get(id).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.state.set('ready');
      },
      error: (error: HttpErrorResponse) => {
        this.state.set(error.status === 404 ? 'not-found' : 'unavailable');
      },
    });
  }
}
