import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

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
      <a routerLink="/integration/flows" routerLinkActive="active">Flows</a>
    </nav>
  `,
  styles: [
    `
      .integration-tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); }
      .integration-tabs a {
        text-decoration: none;
        color: var(--text-muted);
        padding: 9px 14px;
        font-weight: 500;
        border-bottom: 2px solid transparent;
      }
      .integration-tabs a:hover { color: var(--text); }
      .integration-tabs a.active { color: var(--text); font-weight: 600; border-bottom-color: var(--accent); }
    `,
  ],
})
export class IntegrationTabsComponent {}
