import { ChangeDetectionStrategy, Component, effect, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface NavItem {
  path: string;
  label: string;
  code: string;
  exact?: boolean;
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  selector: 'app-sidebar',
  styleUrl: './sidebar.component.css',
  templateUrl: './sidebar.component.html',
})
export class SidebarComponent {
  readonly pinned = signal(true);
  readonly hovering = signal(false);

  readonly operationItems: NavItem[] = [
    { path: '/', label: 'Dashboard', code: 'DS', exact: true },
    { path: '/integration/monitor', label: 'Monitor de mensajes', code: 'MS' },
  ];

  readonly configItems: NavItem[] = [
    { path: '/integration/profiles', label: 'Integration Profiles', code: 'IP' },
    { path: '/integration/connectors', label: 'Conectores y adapters', code: 'CX' },
    { path: '/integration/credentials', label: 'Credenciales', code: 'CR' },
  ];

  constructor() {
    // Drives .rail-spacer's width in app.css (`var(--console-rail-width)`),
    // reserving grid space that matches the rail's *resting* width. Deliberately
    // reacts only to `pinned`, not `hovering`: hovering while unpinned should
    // expand the rail as a flyout overlay (see sidebar.component.css), not
    // reflow `main`.
    effect(() => {
      document.documentElement.style.setProperty('--console-rail-width', this.pinned() ? '232px' : '58px');
    });
  }

  toggle(): void {
    this.pinned.update((value) => !value);
    this.hovering.set(false);
  }

  onEnter(): void {
    if (!this.pinned()) this.hovering.set(true);
  }

  onLeave(): void {
    this.hovering.set(false);
  }
}
