import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  exact?: boolean;
}

// Inner <svg> markup copied verbatim from the Claude Design mock
// (docs/design-system/Integration Console (standalone).html) so the rail
// icons match the design pixel-for-pixel.
const ICON_DASHBOARD = '<path d="M4 15a8 8 0 1 1 16 0"/><path d="M12 15l4-5"/><circle cx="12" cy="15" r="1"/>';
const ICON_MONITOR = '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 6l8 6 8-6"/>';
const ICON_INTEGRATIONS =
  '<path d="M8 15L4 19"/><path d="M15 8l4-4"/><rect x="8.5" y="11.5" width="4" height="4" rx="1" transform="rotate(-45 10.5 13.5)"/><path d="M13 6l5 5"/><path d="M6 13l5 5"/>';
const ICON_CONNECTORS = '<path d="M12 2l9 4.5L12 11 3 6.5 12 2z"/><path d="M3 12l9 4.5 9-4.5"/><path d="M3 17.5l9 4.5 9-4.5"/>';
const ICON_CREDENTIALS = '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9"/><path d="M16 7l3 3"/><path d="M13 10l2.5 2.5"/>';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  selector: 'app-sidebar',
  styleUrl: './sidebar.component.css',
  templateUrl: './sidebar.component.html',
})
export class SidebarComponent {
  private readonly sanitizer = inject(DomSanitizer);

  readonly pinned = signal(true);
  readonly hovering = signal(false);

  readonly operationItems: NavItem[] = [
    { path: '/', label: 'Dashboard', icon: ICON_DASHBOARD, exact: true },
    { path: '/integration/monitor', label: 'Monitor de mensajes', icon: ICON_MONITOR },
  ];

  readonly configItems: NavItem[] = [
    { path: '/integration/profiles', label: 'Integraciones', icon: ICON_INTEGRATIONS },
    { path: '/integration/connectors', label: 'Conectores y adapters', icon: ICON_CONNECTORS },
    { path: '/integration/credentials', label: 'Credenciales', icon: ICON_CREDENTIALS },
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

  iconHtml(icon: string): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(icon);
  }
}
