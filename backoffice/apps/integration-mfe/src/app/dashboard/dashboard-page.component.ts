import { LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IntegrationProfile, SourceOfTruth, SyncDirection } from '../integration-profile/integration-profile.model';
import { IntegrationProfileService } from '../integration-profile/integration-profile.service';

type DashboardState = 'loading' | 'ready' | 'unavailable';

const DIRECTIONS: SyncDirection[] = ['INBOUND', 'OUTBOUND', 'BIDIRECTIONAL'];
const SOTS: SourceOfTruth[] = ['PLATFORM', 'EXTERNAL', 'SHARED'];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LowerCasePipe],
  selector: 'app-dashboard-page',
  standalone: true,
  styleUrl: './dashboard-page.component.css',
  templateUrl: './dashboard-page.component.html',
})
export class DashboardPageComponent implements OnInit {
  private readonly profileService = inject(IntegrationProfileService);

  readonly state = signal<DashboardState>('loading');
  readonly profiles = signal<IntegrationProfile[]>([]);

  readonly activeCount = computed(() => this.profiles().filter((p) => p.active).length);
  readonly inactiveProfiles = computed(() => this.profiles().filter((p) => !p.active));
  readonly inactiveCount = computed(() => this.inactiveProfiles().length);
  readonly sharedSotCount = computed(() => this.profiles().filter((p) => p.sourceOfTruth === 'SHARED').length);

  readonly directionBreakdown = computed(() => {
    const total = this.profiles().length || 1;
    return DIRECTIONS.map((direction) => {
      const count = this.profiles().filter((p) => p.syncDirection === direction).length;
      return { direction, count, pct: Math.round((count / total) * 100) };
    });
  });

  readonly sotBreakdown = computed(() =>
    SOTS.map((sot) => ({ sot, count: this.profiles().filter((p) => p.sourceOfTruth === sot).length })),
  );

  readonly attention = computed(() => this.inactiveProfiles().slice(0, 3));

  ngOnInit(): void {
    this.profileService.list(false).subscribe({
      next: (profiles) => {
        this.profiles.set(profiles);
        this.state.set('ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }
}
