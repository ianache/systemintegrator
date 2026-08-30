import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FlowService } from '../flow/flow.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-tabs',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="integration-tabs" aria-label="Secciones de Integraciones">
      <a routerLink="/integration/profiles" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
        Integration Profiles
      </a>
      <a routerLink="/integration/flows" routerLinkActive="active">
        Flows
        @if (flowCount(); as count) {
          <span class="tab-badge">{{ count }}</span>
        }
      </a>
    </nav>
  `,
  styles: [
    `
      .integration-tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); }
      .integration-tabs a {
        display: flex;
        align-items: center;
        gap: 7px;
        text-decoration: none;
        color: var(--text-muted);
        padding: 9px 14px;
        font-weight: 500;
        border-bottom: 2px solid transparent;
      }
      .integration-tabs a:hover { color: var(--text); }
      .integration-tabs a.active { color: var(--text); font-weight: 600; border-bottom-color: var(--accent); }
      .tab-badge {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 10px;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 3px;
        padding: 1px 5px;
      }
    `,
  ],
})
export class IntegrationTabsComponent implements OnInit {
  private readonly flowService = inject(FlowService);

  readonly flowCount = signal<number | null>(null);

  ngOnInit(): void {
    this.flowService.list().subscribe({
      next: (flows) => this.flowCount.set(flows.length),
      error: () => this.flowCount.set(null),
    });
  }
}
