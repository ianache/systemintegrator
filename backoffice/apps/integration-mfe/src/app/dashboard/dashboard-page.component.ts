import { LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  IntegrationProfile,
  IntegrationProfileStatus,
  SourceOfTruth,
  SyncDirection,
} from '../integration-profile/integration-profile.model';
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

  private static readonly ATTENTION_STATUSES: IntegrationProfileStatus[] = ['PAUSED', 'ERROR', 'DEGRADED'];

  readonly attention = computed(() =>
    this.profiles().filter((p) => DashboardPageComponent.ATTENTION_STATUSES.includes(p.status)).slice(0, 3),
  );

  attentionIssueLabel(status: IntegrationProfileStatus): string {
    const labels: Partial<Record<IntegrationProfileStatus, string>> = {
      PAUSED: 'Pausado por el operador',
      ERROR: 'Última sincronización con error',
      DEGRADED: 'Última sincronización interrumpida',
    };
    return labels[status] ?? '';
  }

  attentionBadgeClass(status: IntegrationProfileStatus): string {
    return 'badge ' + status.toLowerCase();
  }

  attentionStatusLabel(status: IntegrationProfileStatus): string {
    const labels: Partial<Record<IntegrationProfileStatus, string>> = {
      PAUSED: 'Pausado',
      ERROR: 'Con error',
      DEGRADED: 'Degradado',
    };
    return labels[status] ?? status;
  }

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
