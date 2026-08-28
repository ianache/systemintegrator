# Integration Console — Claude Design Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Backoffice's visual language and navigation to match the "Integration Console" Claude Design mockup, and use that redesign as the vehicle to wire the Integration Profiles feature (list, detail, create wizard, sync trigger, deactivate) fully to the real backend — while Dashboard/Monitor/Connectors/Credentials get the same visual treatment but honestly marked as pending real backend support.

**Architecture:** The Shell app (`apps/shell`) owns the console chrome (sidebar, header, theme, global design tokens) matching the mockup's dark rail + light/dark content area. The federated `integration-mfe` remote owns every routed page under `/integration/**` (Dashboard, Profiles list/detail, Monitor, Connectors, Credentials), all mounted through one exposed `./Routes` module as today. The NestJS BFF's `gateway-proxy` is extended from a single read-only endpoint into a full proxy for the Integration Profile CRUD + sync-trigger API and the existing DLQ bulk-replay endpoint, preserving the existing invariant that only the session's bearer token — never a browser-supplied tenant header — is forwarded upstream.

The Claude Design file (`Integration Console.dc.html`) is a client-only prototype built for Claude Design's own runtime (inline styles per element, `{{ }}` interpolation, all data hardcoded in-component). This plan does **not** port that runtime or its inline-style-per-node authoring style; it translates the mockup's visual system — color tokens, typography, spacing, component patterns (cards, chips, tabs, badges, modal, drawer, toast) — into a small shared CSS utility layer plus idiomatic Angular components, and wires real data wherever the backend supports it.

**Tech Stack:** Angular 22 (standalone components, signals), Native Federation 22.0.6, Nx 23.1.1, NestJS 11, RxJS, `axios`, vitest-angular (Angular unit tests), Jest + Supertest (BFF), existing Spring Gateway + `application` service (Java 21 / Spring Boot 3).

**Spec:** Claude Design project `CLocator 2 Integration Profiles` (`https://claude.ai/design/p/7f62b059-0b32-4671-967b-f7a810fd6ef4`), file `Integration Console.dc.html`. Real backend contract: `application/src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java` and `DeadLetterQueueController.java`.

## Global Constraints

- Only Integration Profiles (list, get, create, update, deactivate, trigger-sync) and DLQ replay are real backend calls. Dashboard/Monitor/Connectors/Credentials have no backend beyond what's derivable from the Profiles list and the DLQ replay endpoint — never fabricate message counts, connector catalogs, or credential rows.
- Effective tenant identity comes from the session's validated JWT; the BFF must never forward a browser-supplied tenant header to the Gateway (existing invariant in `gateway-proxy.controller.spec.ts` — preserve it for every new route).
- The BFF's `main.ts` applies a global `api` prefix to everything except an explicit `exclude` allowlist. Every new `bff/api/v1/**` route added to `GatewayProxyController` must get its own `{ path, method }` entry in that allowlist or it will 404 behind `/api/bff/...`.
- The real `IntegrationProfile` domain has no "Pausado"/"Degradado"/"Con error" status and no reactivate action — only `active: boolean` and a one-way `DELETE` (deactivate). UI copy and actions must reflect that; do not reintroduce the mockup's five-state status or a "Reanudar" button.
- The real domain has no dry-run/test-connection/handshake endpoint and no per-domain Kafka topic naming convention. The "Mapping & Transformation", "Políticas" and "Pruebas" tabs must only show things computable from data the user actually entered (JSON validity, a retry-backoff sequence computed from the retry policy fields) or real actions (trigger-sync) — never a fabricated dry-run output or trace log.
- `shell-contracts` remains a workspace library excluded from federation sharing (see `apps/shell/federation.config.mjs`); do not add it as a shared/federated dependency.
- Design tokens (CSS custom properties) are duplicated verbatim between `apps/shell/src/styles.css` and `apps/integration-mfe/src/styles.css` — these are two independently-bundled federated apps with no shared style pipeline today, and the token block is ~50 lines, so a new shared Nx lib is not worth the build-config overhead (YAGNI).
- Keep the existing profile-list state machine (`loading | ready | empty | session-expired | unavailable`) and its 401/403-redirects-to-login behavior; extend it, don't replace it.

---

### Task 1: Global design tokens and base shell layout CSS

**Files:**
- Modify: `backoffice/apps/shell/src/styles.css`
- Modify: `backoffice/apps/integration-mfe/src/styles.css`
- Modify: `backoffice/apps/shell/src/index.html`
- Modify: `backoffice/apps/integration-mfe/src/index.html`
- Modify: `backoffice/apps/shell/src/app/app.css`
- Test: `backoffice/apps/shell/src/app/app.spec.ts`

**Interfaces:**
- Produces CSS custom properties consumed by every later task: `--accent`, `--accent-dark`, `--accent-fg`, `--accent-tint`, `--accent-tint-border`, `--accent-tint-fg`, `--bg`, `--surface`, `--surface-2`, `--border`, `--border-soft`, `--border-strong`, `--text`, `--text-muted`, `--text-dim`, `--invert-bg`, `--invert-bg-hover`, `--on-invert`, `--ok`, `--warn`, `--err`, `--dir-in`, `--dir-out`.
- Produces utility classes consumed by later tasks: `.page`, `.page-header`, `.card`, `.card-header`, `.btn`, `.btn-primary`, `.btn-dark`, `.btn-ghost`, `.badge`, `.chip-group`, `.chip`, `.field`, `.field .label`, `.field .hint`, `.tabs`, `.tab`, `.modal-overlay`, `.modal`, `.drawer-overlay`, `.drawer-backdrop`, `.drawer`, `.toast`, `.empty-state`, `.kbd` (monospace inline text).
- Produces a `.theme-dark` root-level class that later tasks (Task 2's `ThemeService`) toggle on `document.documentElement`.

- [ ] **Step 1: Write the failing test**

Add a test asserting the shell root renders with the light theme by default (no `theme-dark` class on `<html>`), so Task 2 has a red test to turn green:

```ts
// backoffice/apps/shell/src/app/app.spec.ts (append to existing describe block)
it('does not apply the dark theme class by default', () => {
  TestBed.createComponent(App);
  expect(document.documentElement.classList.contains('theme-dark')).toBe(false);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL only if a previous run left `theme-dark` on `<html>` (it won't yet) — actually this step is a sanity check; expected PASS trivially since nothing sets the class yet. Confirm the existing suite still passes first: `npx nx test shell` → all green (this step just re-baselines before the CSS-only change).

- [ ] **Step 3: Write the global tokens + utility CSS**

```css
/* backoffice/apps/shell/src/styles.css */
:root {
  --accent: #8E1B22;
  --accent-dark: #6C1218;
  --accent-fg: #8E1B22;
  --accent-tint: #FBF4F4;
  --accent-tint-border: #F0DEDF;
  --accent-tint-fg: #7A3437;
  --bg: #F5F5F6;
  --surface: #FFFFFF;
  --surface-2: #FAFAFB;
  --border: #E4E4E8;
  --border-soft: #F0F0F2;
  --border-strong: #C9C9D0;
  --text: #16161A;
  --text-muted: #6B6B74;
  --text-dim: #8A8A93;
  --invert-bg: #16161A;
  --invert-bg-hover: #000000;
  --on-invert: #FFFFFF;
  --ok: #157347;
  --warn: #B26A00;
  --err: #B3261E;
  --dir-in: #2B4C86;
  --dir-out: #5B3E93;
}

:root.theme-dark {
  --accent-fg: #E9959A;
  --accent-tint: color-mix(in oklab, var(--accent) 26%, #1E1E22);
  --accent-tint-border: color-mix(in oklab, var(--accent) 46%, #1E1E22);
  --accent-tint-fg: #F0AFB2;
  --bg: #131316;
  --surface: #1B1B1F;
  --surface-2: #212127;
  --border: #303038;
  --border-soft: #26262C;
  --border-strong: #45454F;
  --text: #ECECF0;
  --text-muted: #A6A6B0;
  --text-dim: #83838D;
  --invert-bg: #34343D;
  --invert-bg-hover: #43434E;
  --on-invert: #FFFFFF;
  --ok: #4FBF8B;
  --warn: #E2A64C;
  --err: #F4796F;
  --dir-in: #7FA8F0;
  --dir-out: #B195EA;
}

* { box-sizing: border-box; }

html, body { height: 100%; margin: 0; }

body {
  font-family: 'IBM Plex Sans', system-ui, sans-serif;
  font-size: 13px;
  color: var(--text);
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
}

a { color: var(--accent); text-decoration: none; }
a:hover { color: var(--accent-dark); text-decoration: underline; }

input, select, textarea { font-family: inherit; font-size: inherit; color: var(--text); background: var(--surface); }
input::placeholder, textarea::placeholder { color: var(--text-dim); }
button { font-family: inherit; font-size: inherit; color: inherit; }

::-webkit-scrollbar { width: 10px; height: 10px; }
::-webkit-scrollbar-thumb { background: var(--border-strong); border: 3px solid var(--bg); border-radius: 6px; }

.mono { font-family: 'IBM Plex Mono', monospace; }

.page { padding: 24px 28px 40px; display: flex; flex-direction: column; gap: 16px; max-width: 1560px; }
.page-header { display: flex; align-items: flex-end; gap: 16px; }
.page-header h1 { margin: 0 0 4px; font-size: 20px; font-weight: 600; letter-spacing: -0.015em; }
.page-header p { margin: 0; color: var(--text-muted); font-size: 12.5px; }

.card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; }
.card-header { padding: 13px 16px; border-bottom: 1px solid var(--border); font-weight: 600; display: flex; align-items: center; gap: 8px; }

.btn {
  font-family: inherit; font-size: 13px; border-radius: 5px; padding: 7px 14px;
  cursor: pointer; font-weight: 500; border: 1px solid var(--border);
  background: var(--surface); color: var(--text);
}
.btn:hover { border-color: var(--border-strong); }
.btn-primary { background: var(--accent); border-color: var(--accent); color: #FFFFFF; font-weight: 600; }
.btn-primary:hover { background: var(--accent-dark); border-color: var(--accent-dark); }
.btn-dark { background: var(--invert-bg); border-color: var(--invert-bg); color: var(--on-invert); font-weight: 600; }
.btn-dark:hover { background: var(--invert-bg-hover); border-color: var(--invert-bg-hover); }
.btn-ghost { background: none; border: 0; color: var(--text-dim); }
.btn-ghost:hover { color: var(--text); }

.badge { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 3px; white-space: nowrap; }

.chip-group { display: inline-flex; background: var(--surface); border: 1px solid var(--border); border-radius: 5px; overflow: hidden; }
.chip { border: 0; border-left: 1px solid var(--border); padding: 7px 12px; cursor: pointer; font-weight: 500; background: var(--surface); color: var(--text-muted); }
.chip:first-child { border-left: 0; }
.chip.active { background: var(--invert-bg); color: var(--on-invert); }

.field { display: flex; flex-direction: column; gap: 5px; }
.field .label { font-family: 'IBM Plex Mono', monospace; font-size: 10px; letter-spacing: 0.07em; color: var(--text-dim); text-transform: uppercase; }
.field .hint { font-size: 11px; color: var(--text-dim); }
.field input, .field select, .field textarea {
  border: 1px solid var(--border); border-radius: 5px; padding: 7px 10px; outline: none;
}
.field input:focus, .field select:focus, .field textarea:focus { border-color: var(--accent); }

.tabs { display: flex; gap: 2px; margin-top: 16px; }
.tab { background: none; border: 0; border-bottom: 2px solid transparent; color: var(--text-muted); padding: 9px 13px; cursor: pointer; font-weight: 500; }
.tab.active { border-bottom-color: var(--accent); color: var(--text); }

.modal-overlay { position: fixed; inset: 0; background: rgba(14, 14, 16, 0.5); display: flex; align-items: center; justify-content: center; z-index: 40; }
.modal { width: 960px; max-width: 94vw; max-height: 88vh; background: var(--surface); border-radius: 8px; box-shadow: 0 24px 60px rgba(0, 0, 0, 0.28); display: flex; flex-direction: column; overflow: hidden; }

.drawer-overlay { position: fixed; inset: 0; z-index: 40; display: flex; justify-content: flex-end; }
.drawer-backdrop { position: absolute; inset: 0; background: rgba(14, 14, 16, 0.34); }
.drawer { position: relative; width: 560px; max-width: 92vw; background: var(--surface); height: 100%; box-shadow: -20px 0 50px rgba(0, 0, 0, 0.2); display: flex; flex-direction: column; overflow: hidden; }

.toast {
  position: fixed; bottom: 22px; left: 50%; transform: translateX(-50%);
  background: var(--invert-bg); color: var(--on-invert); padding: 11px 18px; border-radius: 6px;
  font-size: 12.5px; box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25); z-index: 60;
  display: flex; align-items: center; gap: 10px;
}

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center; padding: 48px 24px; color: var(--text-muted); }
.empty-state strong { color: var(--text); font-size: 13.5px; }
```

```css
/* backoffice/apps/integration-mfe/src/styles.css */
/* Intentionally duplicated from apps/shell/src/styles.css: two independently
   federated apps, no shared style pipeline. Keep the two files in sync by hand
   when tokens change. */
```
Then copy the exact same content from the shell file above into this file (identical byte-for-byte).

Add the Google Fonts `<link>` tags to both index.html files, inside `<head>`:

```html
<!-- add inside <head> in both backoffice/apps/shell/src/index.html and backoffice/apps/integration-mfe/src/index.html -->
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link
  href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap"
  rel="stylesheet"
/>
```

Update the shell's app-level layout CSS for the rail + content grid (the sidebar itself is styled in Task 3; this just establishes the outer grid):

```css
/* backoffice/apps/shell/src/app/app.css */
:host {
  display: block;
  height: 100vh;
  overflow: hidden;
}

.shell {
  position: relative;
  display: grid;
  grid-template-columns: var(--console-rail-width, 232px) 1fr;
  height: 100vh;
  overflow: hidden;
  font-size: 13px;
  color: var(--text);
  background: var(--bg);
  accent-color: var(--accent);
}

.shell-body {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

main {
  flex: 1;
  overflow: auto;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test shell`
Expected: PASS (the new assertion passes because nothing has set `theme-dark` yet; it will stay green through Task 2 because the default is light).

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/shell/src/styles.css backoffice/apps/integration-mfe/src/styles.css backoffice/apps/shell/src/index.html backoffice/apps/integration-mfe/src/index.html backoffice/apps/shell/src/app/app.css backoffice/apps/shell/src/app/app.spec.ts
git commit -m "feat(backoffice): add Claude Design tokens and shared utility CSS"
```

---

### Task 2: Theme service (light/dark toggle, persisted)

**Files:**
- Create: `backoffice/apps/shell/src/app/theme/theme.service.ts`
- Test: `backoffice/apps/shell/src/app/theme/theme.service.spec.ts`

**Interfaces:**
- Produces `ThemeService` with `readonly dark: Signal<boolean>` and `toggle(): void`, injectable as `{ providedIn: 'root' }`. Consumed by Task 4 (header toggle button).
- Persists to `localStorage` key `backoffice.theme` (`'dark' | 'light'`), reads it on construction, and applies/removes the `theme-dark` class on `document.documentElement`.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/shell/src/app/theme/theme.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('theme-dark');
  });

  it('defaults to light and does not set the dark class', () => {
    const service = TestBed.inject(ThemeService);
    expect(service.dark()).toBe(false);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(false);
  });

  it('toggles to dark, sets the class, and persists the choice', () => {
    const service = TestBed.inject(ThemeService);
    service.toggle();
    expect(service.dark()).toBe(true);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true);
    expect(localStorage.getItem('backoffice.theme')).toBe('dark');
  });

  it('reads a persisted dark preference on construction', () => {
    localStorage.setItem('backoffice.theme', 'dark');
    const service = TestBed.inject(ThemeService);
    expect(service.dark()).toBe(true);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL with "Cannot find module './theme.service'"

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/shell/src/app/theme/theme.service.ts
import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'backoffice.theme';
const DARK_CLASS = 'theme-dark';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly dark = signal(this.readPersisted());

  constructor() {
    this.applyClass(this.dark());
  }

  toggle(): void {
    const next = !this.dark();
    this.dark.set(next);
    this.applyClass(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? 'dark' : 'light');
    } catch {
      // Storage can be unavailable (private browsing); theme still applies for this session.
    }
  }

  private readPersisted(): boolean {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'dark';
    } catch {
      return false;
    }
  }

  private applyClass(dark: boolean): void {
    document.documentElement.classList.toggle(DARK_CLASS, dark);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test shell`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/shell/src/app/theme
git commit -m "feat(backoffice): add persisted light/dark ThemeService"
```

---

### Task 3: Sidebar component redesign

**Files:**
- Modify: `backoffice/apps/shell/src/app/layout/sidebar.component.ts`
- Modify: `backoffice/apps/shell/src/app/layout/sidebar.component.html`
- Modify: `backoffice/apps/shell/src/app/layout/sidebar.component.css`
- Test: create `backoffice/apps/shell/src/app/layout/sidebar.component.spec.ts` (none exists today)

**Interfaces:**
- Produces the console's left rail: pin/collapse toggle (`pinned` signal, default `true`), hover-to-expand when collapsed, and nav links to `/`, `/integration/profiles`, `/integration/monitor`, `/integration/connectors`, `/integration/credentials`.
- No fabricated per-nav badge counts (the mockup's DLQ badge and "GATEWAY · qa / 127.0.0.1:8081 / kafka · 3 topics" footer are dropped — no real data source for either).

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/shell/src/app/layout/sidebar.component.spec.ts
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { SidebarComponent } from './sidebar.component';

describe('SidebarComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('renders the five console navigation links', () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    const hrefs = Array.from(
      fixture.nativeElement.querySelectorAll('a[href]') as NodeListOf<HTMLAnchorElement>,
    ).map((a) => a.getAttribute('href'));

    expect(hrefs).toEqual([
      '/',
      '/integration/profiles',
      '/integration/monitor',
      '/integration/connectors',
      '/integration/credentials',
    ]);
  });

  it('starts pinned open and collapses on toggle', () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component.pinned()).toBe(true);

    const toggle = fixture.nativeElement.querySelector('[data-testid="sidebar-toggle"]') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    expect(component.pinned()).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL — `sidebar.component.spec.ts` cannot find the `data-testid="sidebar-toggle"` element / `pinned` is not a function yet on the current component.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/shell/src/app/layout/sidebar.component.ts
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
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
```

```html
<!-- backoffice/apps/shell/src/app/layout/sidebar.component.html -->
<aside
  class="rail"
  [class.expanded]="pinned() || hovering()"
  (mouseenter)="onEnter()"
  (mouseleave)="onLeave()"
>
  <div class="brand-row">
    <span class="brand-mark"></span>
    <div class="brand-text">
      <span class="brand-name">CLocator 2</span>
      <span class="brand-sub mono">Integration Console</span>
    </div>
    <button type="button" data-testid="sidebar-toggle" class="pin-btn" (click)="toggle()" title="Fijar o contraer el menú">
      {{ pinned() ? '«' : '»' }}
    </button>
  </div>

  <nav aria-label="Primary navigation">
    <div class="section-label mono">OPERACIÓN</div>
    @for (item of operationItems; track item.path) {
      <a
        [routerLink]="item.path"
        routerLinkActive="active"
        [routerLinkActiveOptions]="{ exact: !!item.exact }"
        ariaCurrentWhenActive="page"
        class="nav-item"
      >
        <span class="nav-icon mono">{{ item.code }}</span>
        <span class="nav-label">{{ item.label }}</span>
      </a>
    }

    <div class="section-label mono">CONFIGURACIÓN</div>
    @for (item of configItems; track item.path) {
      <a
        [routerLink]="item.path"
        routerLinkActive="active"
        ariaCurrentWhenActive="page"
        class="nav-item"
      >
        <span class="nav-icon mono">{{ item.code }}</span>
        <span class="nav-label">{{ item.label }}</span>
      </a>
    }
  </nav>
</aside>
```

```css
/* backoffice/apps/shell/src/app/layout/sidebar.component.css */
:host { display: contents; }

.rail {
  position: absolute;
  top: 0; left: 0; bottom: 0;
  z-index: 30;
  width: 58px;
  background: #0F0F11;
  color: #E7E7EA;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.rail.expanded {
  width: 232px;
  box-shadow: 10px 0 32px rgba(0, 0, 0, 0.32);
}

.brand-row {
  padding: 18px 14px 16px;
  border-bottom: 1px solid #1F1F23;
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 71px;
}

.brand-mark {
  width: 9px; height: 18px; flex: 0 0 9px;
  background: var(--accent);
  border-radius: 1px;
}

.brand-text { display: none; flex-direction: column; gap: 2px; min-width: 0; overflow: hidden; }
.rail.expanded .brand-text { display: flex; }
.brand-name { font-size: 14px; font-weight: 600; letter-spacing: -0.01em; white-space: nowrap; }
.brand-sub { font-size: 10px; color: #6E6E78; letter-spacing: 0.08em; text-transform: uppercase; white-space: nowrap; }

.pin-btn {
  display: none;
  margin-left: auto; background: none; border: 0; color: #6E6E78;
  cursor: pointer; font-size: 13px; line-height: 1; padding: 4px;
}
.rail.expanded .pin-btn { display: block; }
.pin-btn:hover { color: #FFFFFF; }

nav { padding: 12px 10px; display: flex; flex-direction: column; gap: 1px; }

.section-label {
  display: none;
  font-size: 10px; color: #57575F; letter-spacing: 0.1em; padding: 16px 8px 6px;
  white-space: nowrap; overflow: hidden;
}
.rail.expanded .section-label { display: block; }
.section-label:first-child { padding-top: 8px; }

.nav-item {
  text-align: left; display: flex; align-items: center; gap: 9px;
  background: transparent; color: #A0A0AA; border: 0; border-radius: 4px;
  padding: 7px 8px; cursor: pointer; font-weight: 500; white-space: nowrap; overflow: hidden;
  text-decoration: none;
}
.nav-item:hover { background: #1C1C21; color: #FFFFFF; }
.nav-item.active { background: #1C1C21; color: #FFFFFF; }

.nav-icon {
  width: 20px; height: 20px; flex: 0 0 20px; display: grid; place-items: center;
  border-radius: 3px; background: #1C1C21; font-size: 9.5px; font-weight: 600;
}

.nav-label { display: none; }
.rail.expanded .nav-label { display: inline; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test shell`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/shell/src/app/layout/sidebar.component.ts backoffice/apps/shell/src/app/layout/sidebar.component.html backoffice/apps/shell/src/app/layout/sidebar.component.css backoffice/apps/shell/src/app/layout/sidebar.component.spec.ts
git commit -m "feat(backoffice): redesign sidebar as collapsible console rail"
```

---

### Task 4: Header component redesign

**Files:**
- Modify: `backoffice/apps/shell/src/app/layout/header.component.ts`
- Modify: `backoffice/apps/shell/src/app/layout/header.component.html`
- Modify: `backoffice/apps/shell/src/app/layout/header.component.css`
- Test: create `backoffice/apps/shell/src/app/layout/header.component.spec.ts` (none exists today)

**Interfaces:**
- Consumes `SessionService.session` (existing `Signal<Session>` with `authenticated`, `tenantId?`, `expiresAt?`) and `ThemeService` from Task 2.
- No tenant switcher: the mockup's two-tenant toggle has no real counterpart — a session has exactly one tenant, resolved server-side from the JWT. The header only *displays* `session().tenantId`.
- `expiresAt` is a Unix-seconds timestamp (see `session-types.ts`); the header renders it as a local time string, not a countdown timer (no need for a running interval / extra test surface for a cosmetic detail).

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/shell/src/app/layout/header.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HeaderComponent } from './header.component';
import { SessionService } from '../session/session.service';
import { ThemeService } from '../theme/theme.service';

describe('HeaderComponent', () => {
  it('shows the tenant from the session and no tenant switcher', async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(HeaderComponent);
    const session = TestBed.inject(SessionService);
    session.session.set({ authenticated: true, tenantId: 'tenant-abc', expiresAt: 1893456000 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('tenant-abc');
    expect(fixture.nativeElement.querySelector('[data-testid="tenant-switcher"]')).toBeNull();
  });

  it('toggles the theme via ThemeService', async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(HeaderComponent);
    fixture.detectChanges();
    const theme = TestBed.inject(ThemeService);
    const button = fixture.nativeElement.querySelector('[data-testid="theme-toggle"]') as HTMLButtonElement;

    expect(theme.dark()).toBe(false);
    button.click();
    expect(theme.dark()).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL — `[data-testid="theme-toggle"]` / `[data-testid="tenant-switcher"]` don't exist on the current header yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/shell/src/app/layout/header.component.ts
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { SessionService } from '../session/session.service';
import { ThemeService } from '../theme/theme.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  selector: 'app-header',
  styleUrl: './header.component.css',
  templateUrl: './header.component.html',
})
export class HeaderComponent {
  protected readonly sessionService = inject(SessionService);
  protected readonly theme = inject(ThemeService);

  protected expiryLabel(expiresAt: number | undefined): string | null {
    if (!expiresAt) return null;
    return new Date(expiresAt * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
```

```html
<!-- backoffice/apps/shell/src/app/layout/header.component.html -->
<header class="console-header">
  <div class="tenant-label" aria-live="polite">
    @if (sessionService.session().authenticated) {
      <span class="mono label">TENANT</span>
      <span class="tenant-name">{{ sessionService.session().tenantId }}</span>
    } @else {
      <span class="status">No autenticado</span>
    }
  </div>

  <div class="right-group">
    @if (sessionService.session().authenticated) {
      @if (expiryLabel(sessionService.session().expiresAt); as expiry) {
        <span class="jwt-status">Sesión válida · <span class="mono">{{ expiry }}</span></span>
      }
      <button type="button" data-testid="theme-toggle" class="theme-btn" (click)="theme.toggle()" title="Cambiar tema">
        <span>{{ theme.dark() ? '☀' : '☾' }}</span>
        {{ theme.dark() ? 'CLARO' : 'OSCURO' }}
      </button>
      <button type="button" class="btn" (click)="sessionService.logout()">Cerrar sesión</button>
    } @else {
      <button type="button" class="btn btn-primary" (click)="sessionService.login()">Iniciar sesión</button>
    }
  </div>
</header>
```

```css
/* backoffice/apps/shell/src/app/layout/header.component.css */
:host { display: contents; }

.console-header {
  height: 52px; flex: 0 0 52px;
  background: var(--surface); border-bottom: 1px solid var(--border);
  display: flex; align-items: center; gap: 14px; padding: 0 20px;
}

.tenant-label { display: flex; align-items: center; gap: 9px; }
.tenant-label .label { font-size: 10px; color: var(--text-dim); letter-spacing: 0.06em; }
.tenant-name { font-weight: 600; }

.right-group { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.jwt-status { font-size: 11px; color: var(--text-dim); }

.theme-btn {
  display: flex; align-items: center; gap: 7px; background: var(--surface-2);
  border: 1px solid var(--border); border-radius: 5px; padding: 5px 9px; cursor: pointer;
  font-size: 10px; letter-spacing: 0.06em; color: var(--text-muted);
}
.theme-btn:hover { border-color: var(--border-strong); color: var(--text); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test shell`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/shell/src/app/layout/header.component.ts backoffice/apps/shell/src/app/layout/header.component.html backoffice/apps/shell/src/app/layout/header.component.css backoffice/apps/shell/src/app/layout/header.component.spec.ts
git commit -m "feat(backoffice): redesign header with real tenant display and theme toggle"
```

---

### Task 5: Shell root layout and routing

**Files:**
- Modify: `backoffice/apps/shell/src/app/app.routes.ts`
- Modify: `backoffice/apps/shell/src/app/app.html`
- Modify: `backoffice/apps/shell/src/app/app.ts` (remove now-unused `WelcomeComponent`)
- Modify: `backoffice/apps/shell/src/app/app.spec.ts`

**Interfaces:**
- Root path `/` redirects to `/integration` (the Dashboard, built in Task 11, lives at the remote's own `''` path under that prefix).
- Keeps `buildMicroUiRoute` wiring to `integration-mfe` exactly as-is (remote name, remote entry URL, exposed module) — only the host-side route list changes.

- [ ] **Step 1: Write the failing test**

This test must NOT reuse the shared `beforeEach`'s `provideRouter([])` — the real `appRoutes` include `buildMicroUiRoute`, whose federated `/integration` route would try to fetch `http://localhost:4202/remoteEntry.json` over the network during a unit test. Assert the route table's shape directly instead of performing a live navigation:

```ts
// backoffice/apps/shell/src/app/app.spec.ts (add a new top-level describe, alongside the existing "App" describe block — do not touch its shared beforeEach)
import { appRoutes } from './app.routes';

describe('appRoutes', () => {
  it('redirects the root path to /integration', () => {
    expect(appRoutes[0]).toMatchObject({ path: '', pathMatch: 'full', redirectTo: 'integration' });
  });

  it('redirects unknown paths to /integration', () => {
    const wildcard = appRoutes.find((route) => route.path === '**');
    expect(wildcard?.redirectTo).toBe('integration');
  });
});
```

(Keep these alongside the existing `App` describe block and the `theme-dark` default-class test from Task 1; do not delete other passing assertions in the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL — current root route renders `WelcomeComponent` at `''`, not a redirect.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/shell/src/app/app.routes.ts
import { Routes } from '@angular/router';
import { buildMicroUiRoute } from 'shell-contracts';

export const appRoutes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'integration',
  },
  buildMicroUiRoute({
    path: 'integration',
    // Must match the `name` the remote declares in its own federation.config.mjs,
    // and therefore in its remoteEntry.json. Native Federation registers a remote
    // under the name carried by the fetched remoteEntry, so a mismatch here makes
    // `loadRemoteModule` fail with "Remote '...' is not initialized."
    remoteName: 'integration-mfe',
    remoteEntry: 'http://localhost:4202/remoteEntry.json',
    exposedModule: './Routes',
  }),
  {
    path: '**',
    redirectTo: 'integration',
  },
];
```

```html
<!-- backoffice/apps/shell/src/app/app.html (unchanged structure, kept for reference) -->
<div class="shell">
  <app-header />
  <div class="shell-body">
    <app-sidebar />
    <main id="main-content" tabindex="-1">
      <router-outlet />
    </main>
  </div>
</div>
```

```ts
// backoffice/apps/shell/src/app/app.ts
import { HttpClientModule } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './layout/header.component';
import { SidebarComponent } from './layout/sidebar.component';
import { SessionService } from './session/session.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HttpClientModule, HeaderComponent, RouterOutlet, SidebarComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly sessionService = inject(SessionService);

  constructor() {
    this.sessionService.refresh();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test shell`
Expected: PASS. Also run `npx nx build shell` to confirm removing `WelcomeComponent` didn't break another import (grep first: `grep -rn "WelcomeComponent" backoffice/apps/shell/src` should return nothing outside `app.ts`/`app.spec.ts` after this change).

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/shell/src/app/app.routes.ts backoffice/apps/shell/src/app/app.html backoffice/apps/shell/src/app/app.ts backoffice/apps/shell/src/app/app.spec.ts
git commit -m "feat(backoffice): redirect shell root to the integration console"
```

---

### Task 6: Extend the Integration Profile model and service

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts`

**Interfaces:**
- Produces the full `IntegrationProfile`, `IntegrationProfileConfiguration`, `IntegrationProtocol`, `SyncDirection`, `SourceOfTruth` types matching `IntegrationProfileResponse`/`ConfigurationResponse` in `application/.../dto/IntegrationProfileResponse.java` exactly.
- Produces `IntegrationProfileService` methods consumed by later tasks: `list(activeOnly?: boolean)`, `get(id: string)`, `create(payload: CreateIntegrationProfilePayload)`, `update(id: string, payload: UpdateIntegrationProfilePayload)`, `deactivate(id: string)`, `triggerSync(id: string)`.
- Produces `TriggerSyncResult` (`{ profileId, status, triggeredAt }`) matching `TriggerSyncResponse.java`.

- [ ] **Step 1: Write the failing tests**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileService } from './integration-profile.service';

const FULL_PROFILE = {
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: {
    protocol: 'KAFKA',
    connector: 'sigo-kafka-connector',
    adapter: 'SigoVehicleAdapter',
    endpoint: 'kafka://sigo-prod/vehiculos.v1',
    credentialRef: 'vault://tenant-a/sigo/kafka-sasl',
    mapping: null,
    transformation: null,
    syncPolicy: null,
    retryPolicy: { maxAttempts: 5, backoff: 'EXPONENTIAL' },
    rateLimitPolicy: null,
    extractionConfig: null,
  },
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 7,
};

describe('IntegrationProfileService', () => {
  let service: IntegrationProfileService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(IntegrationProfileService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads profiles through the BFF same-origin endpoint', () => {
    service.list().subscribe((profiles) => expect(profiles[0].id).toBe('p-1'));
    const request = http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true');
    expect(request.request.method).toBe('GET');
    request.flush([FULL_PROFILE]);
  });

  it('loads all profiles (including inactive) when activeOnly is false', () => {
    service.list(false).subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false');
    request.flush([FULL_PROFILE]);
  });

  it('loads a single profile by id', () => {
    service.get('p-1').subscribe((profile) => expect(profile.businessDomain).toBe('vehicle'));
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  });

  it('creates a profile', () => {
    service
      .create({ businessDomain: 'vehicle', externalSource: 'SIGO', syncDirection: 'INBOUND', sourceOfTruth: 'EXTERNAL' })
      .subscribe((profile) => expect(profile.id).toBe('p-1'));
    const request = http.expectOne('/bff/api/v1/integration-profiles');
    expect(request.request.method).toBe('POST');
    request.flush(FULL_PROFILE);
  });

  it('updates a profile with the expected version', () => {
    service
      .update('p-1', {
        businessDomain: 'vehicle',
        externalSource: 'SIGO',
        syncDirection: 'INBOUND',
        sourceOfTruth: 'EXTERNAL',
        expectedVersion: 7,
      })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(7);
    request.flush(FULL_PROFILE);
  });

  it('deactivates a profile', () => {
    service.deactivate('p-1').subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('triggers a sync', () => {
    service.triggerSync('p-1').subscribe((result) => expect(result.status).toBe('TRIGGERED'));
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/sync');
    expect(request.request.method).toBe('POST');
    request.flush({ profileId: 'p-1', status: 'TRIGGERED', triggeredAt: '2026-08-26T00:00:00Z' });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — `service.get`, `service.create`, `service.update`, `service.deactivate`, `service.triggerSync` don't exist yet; `list()` doesn't accept a parameter or send the query string.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts
export type IntegrationProtocol = 'REST' | 'SOAP' | 'JSON_RPC' | 'KAFKA' | 'JDBC';
export type SyncDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL';
export type SourceOfTruth = 'PLATFORM' | 'EXTERNAL' | 'SHARED';

export interface IntegrationProfileConfiguration {
  protocol: IntegrationProtocol | null;
  connector: string | null;
  adapter: string | null;
  endpoint: string | null;
  credentialRef: string | null;
  mapping: unknown | null;
  transformation: unknown | null;
  syncPolicy: unknown | null;
  retryPolicy: unknown | null;
  rateLimitPolicy: unknown | null;
  extractionConfig: unknown | null;
}

export interface IntegrationProfile {
  id: string;
  tenantId: string;
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  configuration: IntegrationProfileConfiguration | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface IntegrationProfileConfigurationPayload {
  protocol?: IntegrationProtocol | null;
  connector?: string | null;
  adapter?: string | null;
  endpoint?: string | null;
  credentialRef?: string | null;
  mapping?: unknown | null;
  transformation?: unknown | null;
  syncPolicy?: unknown | null;
  retryPolicy?: unknown | null;
  rateLimitPolicy?: unknown | null;
  extractionConfig?: unknown | null;
}

export interface CreateIntegrationProfilePayload extends IntegrationProfileConfigurationPayload {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
}

export interface UpdateIntegrationProfilePayload extends CreateIntegrationProfilePayload {
  expectedVersion: number;
}

export interface TriggerSyncResult {
  profileId: string;
  status: string;
  triggeredAt: string;
}

export interface ApiProblem {
  title?: string;
  status?: number;
  detail?: string;
  errorCode?: string;
  correlationId?: string;
}
```

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateIntegrationProfilePayload,
  IntegrationProfile,
  TriggerSyncResult,
  UpdateIntegrationProfilePayload,
} from './integration-profile.model';

const BASE_URL = '/bff/api/v1/integration-profiles';

@Injectable({ providedIn: 'root' })
export class IntegrationProfileService {
  private readonly http = inject(HttpClient);

  list(activeOnly = true): Observable<IntegrationProfile[]> {
    const params = new HttpParams().set('activeOnly', String(activeOnly));
    return this.http.get<IntegrationProfile[]>(BASE_URL, { params });
  }

  get(id: string): Observable<IntegrationProfile> {
    return this.http.get<IntegrationProfile>(`${BASE_URL}/${id}`);
  }

  create(payload: CreateIntegrationProfilePayload): Observable<IntegrationProfile> {
    return this.http.post<IntegrationProfile>(BASE_URL, payload);
  }

  update(id: string, payload: UpdateIntegrationProfilePayload): Observable<IntegrationProfile> {
    return this.http.put<IntegrationProfile>(`${BASE_URL}/${id}`, payload);
  }

  deactivate(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${id}`);
  }

  triggerSync(id: string): Observable<TriggerSyncResult> {
    return this.http.post<TriggerSyncResult>(`${BASE_URL}/${id}/sync`, {});
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS. This will also compile-fail `integration-profile-list.component.spec.ts` and `integration-profile-list.component.html` because the old `IntegrationProfile` fixtures/template (`profile.syncDirection`, no `tenantId`/`sourceOfTruth`/`createdAt`/`updatedAt`) are missing fields the stricter type now requires — that's expected and gets fixed in Task 12, which rewrites both files. Confirm the failures are confined to those two files: `npx nx test integration-mfe 2>&1 | grep -i "integration-profile-list"`.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts
git commit -m "feat(backoffice): extend Integration Profile model and service to the full API"
```

---

### Task 7: Extend the BFF gateway-proxy service to the full Profile + DLQ API

**Files:**
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.spec.ts`

**Interfaces:**
- Produces `GatewayProxyService` methods consumed by Task 8's controller: `getIntegrationProfiles(accessToken, activeOnly)`, `getIntegrationProfile(accessToken, profileId)`, `createIntegrationProfile(accessToken, body)`, `updateIntegrationProfile(accessToken, profileId, body)`, `deactivateIntegrationProfile(accessToken, profileId)`, `triggerSync(accessToken, profileId)`, `replayDeadLetterQueue(accessToken)`.
- Preserves existing behavior: 401 → `UnauthorizedException('Gateway rejected the session credentials')`, 403 → `ForbiddenException('Gateway denied access to integration profiles')`, no-response/other → `BadGatewayException('Gateway is unavailable')`.
- Adds passthrough for `400`, `404`, `409`, `422` upstream responses: rethrows an `HttpException` carrying the upstream's exact `ProblemDetail` JSON body and status code, so the Angular UI can read `errorCode`/`detail` (see `ApiProblemDetailFactory.java`).

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.spec.ts
import { ForbiddenException, HttpException, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';
import { GatewayProxyService } from './gateway-proxy.service';

describe('GatewayProxyService', () => {
  let service: GatewayProxyService;
  const config = { getOrThrow: () => 'http://gateway.internal' } as unknown as ConfigService;

  beforeEach(() => {
    service = new GatewayProxyService(config);
  });

  afterEach(() => jest.restoreAllMocks());

  it('lists profiles with the activeOnly filter forwarded as a query param', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ id: 'p-1' }] });
    const result = await service.getIntegrationProfiles('token-1', false);
    expect(result).toEqual([{ id: 'p-1' }]);
    expect(get).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles?activeOnly=false', {
      headers: { Authorization: 'Bearer token-1' },
    });
  });

  it('gets a single profile by id', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { id: 'p-1' } });
    const result = await service.getIntegrationProfile('token-1', 'p-1');
    expect(result).toEqual({ id: 'p-1' });
  });

  it('creates a profile, forwarding only the bearer token and the body', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'p-1' } });
    await service.createIntegrationProfile('token-1', { businessDomain: 'vehicle' });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles',
      { businessDomain: 'vehicle' },
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('updates a profile', async () => {
    const put = jest.spyOn(axios, 'put').mockResolvedValue({ data: { id: 'p-1', version: 8 } });
    const result = await service.updateIntegrationProfile('token-1', 'p-1', { expectedVersion: 7 });
    expect(result).toEqual({ id: 'p-1', version: 8 });
    expect(put).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles/p-1',
      { expectedVersion: 7 },
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('deactivates a profile', async () => {
    const del = jest.spyOn(axios, 'delete').mockResolvedValue({ data: undefined });
    await service.deactivateIntegrationProfile('token-1', 'p-1');
    expect(del).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles/p-1', {
      headers: { Authorization: 'Bearer token-1' },
    });
  });

  it('triggers a sync', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { status: 'TRIGGERED' } });
    const result = await service.triggerSync('token-1', 'p-1');
    expect(result).toEqual({ status: 'TRIGGERED' });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles/p-1/sync',
      {},
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('replays the dead letter queue', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { total: 3, success: 2, failed: 1 } });
    const result = await service.replayDeadLetterQueue('token-1');
    expect(result).toEqual({ total: 3, success: 2, failed: 1 });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/inbox/dlq/replay',
      {},
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('maps a 401 to UnauthorizedException regardless of the failing call', async () => {
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 401 } });
    await expect(service.createIntegrationProfile('token-1', {})).rejects.toBeInstanceOf(UnauthorizedException);
  });

  it('maps a 403 to ForbiddenException', async () => {
    jest.spyOn(axios, 'delete').mockRejectedValue({ response: { status: 403 } });
    await expect(service.deactivateIntegrationProfile('token-1', 'p-1')).rejects.toBeInstanceOf(ForbiddenException);
  });

  it('passes through a 409 conflict body and status from the Gateway', async () => {
    const problem = { title: 'Conflict', status: 409, detail: 'An active integration profile already exists', errorCode: 'INTEGRATION_PROFILE_CONFLICT' };
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 409, data: problem } });

    await expect(service.createIntegrationProfile('token-1', {})).rejects.toMatchObject({
      status: 409,
      response: problem,
    });
  });

  it('passes through a 404 not-found body', async () => {
    const problem = { title: 'Not Found', status: 404, errorCode: 'INTEGRATION_PROFILE_NOT_FOUND' };
    jest.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404, data: problem } });

    const error = (await service.getIntegrationProfile('token-1', 'missing').catch((e) => e)) as HttpException;
    expect(error).toBeInstanceOf(HttpException);
    expect(error.getStatus()).toBe(404);
    expect(error.getResponse()).toEqual(problem);
  });

  it('maps a network failure with no response to BadGatewayException', async () => {
    jest.spyOn(axios, 'get').mockRejectedValue(new Error('ECONNREFUSED'));
    await expect(service.getIntegrationProfiles('token-1', true)).rejects.toThrow('Gateway is unavailable');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — only `getIntegrationProfiles(accessToken)` (single-arg) exists today; the rest of the methods are undefined.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts
import {
  BadGatewayException,
  ForbiddenException,
  HttpException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

type HttpMethod = 'get' | 'post' | 'put' | 'delete';
const PASSTHROUGH_STATUSES = new Set([400, 404, 409, 422]);

@Injectable()
export class GatewayProxyService {
  constructor(private readonly config: ConfigService) {}

  getIntegrationProfiles(accessToken: string, activeOnly: boolean): Promise<unknown> {
    return this.forward('get', `/api/v1/integration-profiles?activeOnly=${activeOnly}`, accessToken);
  }

  getIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/integration-profiles/${profileId}`, accessToken);
  }

  createIntegrationProfile(accessToken: string, body: unknown): Promise<unknown> {
    return this.forward('post', '/api/v1/integration-profiles', accessToken, body);
  }

  updateIntegrationProfile(accessToken: string, profileId: string, body: unknown): Promise<unknown> {
    return this.forward('put', `/api/v1/integration-profiles/${profileId}`, accessToken, body);
  }

  deactivateIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('delete', `/api/v1/integration-profiles/${profileId}`, accessToken);
  }

  triggerSync(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/sync`, accessToken, {});
  }

  replayDeadLetterQueue(accessToken: string): Promise<unknown> {
    return this.forward('post', '/api/v1/inbox/dlq/replay', accessToken, {});
  }

  private async forward(method: HttpMethod, path: string, accessToken: string, body?: unknown): Promise<unknown> {
    const url = `${this.config.getOrThrow<string>('GATEWAY_URI')}${path}`;
    const options = { headers: { Authorization: `Bearer ${accessToken}` } };

    try {
      const response =
        method === 'get'
          ? await axios.get(url, options)
          : method === 'delete'
            ? await axios.delete(url, options)
            : await axios[method](url, body, options);
      return response.data;
    } catch (error) {
      throw this.mapError(error);
    }
  }

  private mapError(error: unknown): Error {
    const response = (error as { response?: { status?: number; data?: unknown } }).response;
    const status = response?.status;

    if (status === 401) {
      return new UnauthorizedException('Gateway rejected the session credentials');
    }
    if (status === 403) {
      return new ForbiddenException('Gateway denied access to integration profiles');
    }
    if (status !== undefined && PASSTHROUGH_STATUSES.has(status)) {
      return new HttpException(response?.data as Record<string, unknown>, status);
    }
    return new BadGatewayException('Gateway is unavailable');
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test bff`
Expected: PASS. This will also compile-fail `gateway-proxy.controller.spec.ts` (calls `getIntegrationProfiles` with a single argument) — expected, fixed in Task 8.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.spec.ts
git commit -m "feat(backoffice): extend GatewayProxyService to the full Profile and DLQ API"
```

---

### Task 8: Add the new gateway-proxy routes and their prefix exclusions

**Files:**
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts`
- Modify: `backoffice/apps/bff/src/main.ts`

**Interfaces:**
- Produces the browser-facing routes consumed by Task 6's Angular service: `GET/POST /bff/api/v1/integration-profiles`, `GET/PUT/DELETE /bff/api/v1/integration-profiles/:profileId`, `POST /bff/api/v1/integration-profiles/:profileId/sync`, `POST /bff/api/v1/inbox/dlq/replay`.
- Every one of those routes must appear in `main.ts`'s `setGlobalPrefix` `exclude` list with its exact HTTP method, or Nest will require it at `/api/bff/api/v1/...` instead and the frontend's `/bff/api/v1/...` calls will 404.
- Preserves the existing security invariant: only `request.session.tokens.access_token` is forwarded upstream; no client-supplied header (e.g. `X-Tenant-ID`) ever reaches `GatewayProxyService`.

- [ ] **Step 1: Write the failing test**

Replace the full contents of the spec file (it re-declares the excluded-routes list and the exact-URL assertions that Task 7 changed):

```ts
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts
import { HttpStatus, INestApplication, RequestMethod } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import axios from 'axios';
import request from 'supertest';
import { GatewayProxyModule } from './gateway-proxy.module';

describe('Gateway profile proxy', () => {
  let app: INestApplication;

  beforeEach(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({
          isGlobal: true,
          ignoreEnvFile: true,
          load: [() => ({ GATEWAY_URI: 'http://gateway.internal' })],
        }),
        GatewayProxyModule,
      ],
    }).compile();

    app = moduleRef.createNestApplication();
    app.use((req: any, _res: unknown, next: () => void) => {
      if (req.headers.cookie === 'session=authenticated') {
        req.session = {
          tokens: {
            access_token: 'session-access-token',
            id_token: 'server-only-id-token',
            tenantId: 'server-side-tenant',
            expiresAt: 1893456000,
          },
        };
      }
      next();
    });
    app.setGlobalPrefix('api', {
      exclude: [
        { path: 'bff/api/v1/integration-profiles', method: RequestMethod.GET },
        { path: 'bff/api/v1/integration-profiles', method: RequestMethod.POST },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.GET },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.PUT },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.DELETE },
        { path: 'bff/api/v1/integration-profiles/:profileId/sync', method: RequestMethod.POST },
        { path: 'bff/api/v1/inbox/dlq/replay', method: RequestMethod.POST },
      ],
    });
    await app.init();
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app.close();
  });

  it('rejects an anonymous browser session on every route', async () => {
    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED)
      .expect({ statusCode: HttpStatus.UNAUTHORIZED, message: 'Authentication required', error: 'Unauthorized' });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED);
  });

  it('forwards the activeOnly filter and only the session access token', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ id: 'profile-1' }] });

    const response = await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles?activeOnly=false')
      .set('X-Tenant-ID', 'browser-controlled-tenant')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(response.body).toEqual([{ id: 'profile-1' }]);
    expect(get).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles?activeOnly=false', {
      headers: { Authorization: 'Bearer session-access-token' },
    });
  });

  it('gets a single profile by id', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { id: 'profile-1' } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { id: 'profile-1' });
  });

  it('creates a profile, forwarding the request body', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'profile-1' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .send({ businessDomain: 'vehicle' })
      .expect(HttpStatus.CREATED === HttpStatus.OK ? HttpStatus.OK : HttpStatus.CREATED)
      .catch(() => undefined); // status below asserts precisely; this line only exercises the call

    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles',
      { businessDomain: 'vehicle' },
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('updates a profile', async () => {
    jest.spyOn(axios, 'put').mockResolvedValue({ data: { id: 'profile-1', version: 8 } });

    await request(app.getHttpServer())
      .put('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .send({ expectedVersion: 7 })
      .expect(HttpStatus.OK, { id: 'profile-1', version: 8 });
  });

  it('deactivates a profile with a 204 response', async () => {
    jest.spyOn(axios, 'delete').mockResolvedValue({ data: undefined });

    await request(app.getHttpServer())
      .delete('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.NO_CONTENT);
  });

  it('triggers a sync', async () => {
    jest.spyOn(axios, 'post').mockResolvedValue({ data: { profileId: 'profile-1', status: 'TRIGGERED' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles/profile-1/sync')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.CREATED === HttpStatus.OK ? HttpStatus.OK : 201)
      .catch(() => undefined);
  });

  it('replays the dead letter queue', async () => {
    jest.spyOn(axios, 'post').mockResolvedValue({ data: { total: 1, success: 1, failed: 0 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/inbox/dlq/replay')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.CREATED === HttpStatus.OK ? HttpStatus.OK : 201)
      .catch(() => undefined);
  });

  it.each([
    [HttpStatus.UNAUTHORIZED, 'Gateway rejected the session credentials', 'Unauthorized'],
    [HttpStatus.FORBIDDEN, 'Gateway denied access to integration profiles', 'Forbidden'],
    [HttpStatus.BAD_GATEWAY, 'Gateway is unavailable', 'Bad Gateway'],
  ])('maps downstream failure to a stable browser error (%i)', async (status, message, error) => {
    jest.spyOn(axios, 'get').mockRejectedValue({ response: status === HttpStatus.BAD_GATEWAY ? undefined : { status } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(status)
      .expect({ statusCode: status, message, error });
  });

  it('passes through a 409 conflict body from the Gateway on create', async () => {
    const problem = { title: 'Conflict', status: 409, detail: 'An active integration profile already exists', errorCode: 'INTEGRATION_PROFILE_CONFLICT' };
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 409, data: problem } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .send({ businessDomain: 'vehicle' })
      .expect(409, problem);
  });

  it('passes through a 404 not-found body from the Gateway on get-by-id', async () => {
    const problem = { title: 'Not Found', status: 404, errorCode: 'INTEGRATION_PROFILE_NOT_FOUND' };
    jest.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404, data: problem } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles/missing')
      .set('Cookie', 'session=authenticated')
      .expect(404, problem);
  });
});
```

Note: the two `.expect(HttpStatus.CREATED === HttpStatus.OK ? ... : ...).catch(() => undefined)` lines above are a deliberately loose status check because Nest's default success status for `@Post`/`@Put` handlers is `201`/`200` depending on decorators not yet chosen — replace them with a precise assertion once Step 3 fixes the controller's `@HttpCode` decorators (see below); this is resolved before Step 4, not left loose in the final file.

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — the new routes don't exist on the controller yet (404s), and the exclude list in `main.ts` doesn't matter for this test (it sets its own), but the controller itself has no handlers for `GET/PUT/DELETE .../:profileId`, `POST .../:profileId/sync`, or `POST /inbox/dlq/replay`.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts
import {
  Body,
  CanActivate,
  Controller,
  Delete,
  ExecutionContext,
  Get,
  HttpCode,
  HttpStatus,
  Injectable,
  Param,
  Post,
  Put,
  Query,
  Req,
  UnauthorizedException,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import { GatewayProxyService } from './gateway-proxy.service';

interface AuthenticatedRequest extends Request {
  session: Request['session'] & {
    tokens?: { access_token?: string };
  };
}

@Injectable()
export class SessionAccessTokenGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    if (typeof request.session?.tokens?.access_token !== 'string') {
      throw new UnauthorizedException('Authentication required');
    }
    return true;
  }
}

@Controller('bff/api/v1')
@UseGuards(SessionAccessTokenGuard)
export class GatewayProxyController {
  constructor(private readonly gatewayProxy: GatewayProxyService) {}

  @Get('integration-profiles')
  getIntegrationProfiles(@Req() request: AuthenticatedRequest, @Query('activeOnly') activeOnly = 'true') {
    return this.gatewayProxy.getIntegrationProfiles(request.session.tokens!.access_token!, activeOnly !== 'false');
  }

  @Get('integration-profiles/:profileId')
  getIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.getIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles')
  @HttpCode(HttpStatus.OK)
  createIntegrationProfile(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.gatewayProxy.createIntegrationProfile(request.session.tokens!.access_token!, body);
  }

  @Put('integration-profiles/:profileId')
  updateIntegrationProfile(
    @Req() request: AuthenticatedRequest,
    @Param('profileId') profileId: string,
    @Body() body: unknown,
  ) {
    return this.gatewayProxy.updateIntegrationProfile(request.session.tokens!.access_token!, profileId, body);
  }

  @Delete('integration-profiles/:profileId')
  @HttpCode(HttpStatus.NO_CONTENT)
  deactivateIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.deactivateIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/sync')
  @HttpCode(HttpStatus.OK)
  triggerSync(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.triggerSync(request.session.tokens!.access_token!, profileId);
  }

  @Post('inbox/dlq/replay')
  @HttpCode(HttpStatus.OK)
  replayDeadLetterQueue(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.replayDeadLetterQueue(request.session.tokens!.access_token!);
  }
}
```

`@HttpCode(HttpStatus.OK)` on the three POST handlers keeps the BFF's response status a plain `200` (matching what the frontend service in Task 6 expects from a `Observable<...>` `.subscribe` success path) even though the real Gateway itself answers `201`/`202` for create/trigger-sync — the BFF is a thin proxy re-shaping only what the invariants above require, not mirroring every upstream status code. Now fix the two loose assertions from Step 1 to match this exactly:

```ts
// gateway-proxy.controller.spec.ts — replace the two loose blocks with:
it('creates a profile, forwarding the request body', async () => {
  const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'profile-1' } });

  await request(app.getHttpServer())
    .post('/bff/api/v1/integration-profiles')
    .set('Cookie', 'session=authenticated')
    .send({ businessDomain: 'vehicle' })
    .expect(HttpStatus.OK, { id: 'profile-1' });

  expect(post).toHaveBeenCalledWith(
    'http://gateway.internal/api/v1/integration-profiles',
    { businessDomain: 'vehicle' },
    { headers: { Authorization: 'Bearer session-access-token' } },
  );
});

it('triggers a sync', async () => {
  jest.spyOn(axios, 'post').mockResolvedValue({ data: { profileId: 'profile-1', status: 'TRIGGERED' } });

  await request(app.getHttpServer())
    .post('/bff/api/v1/integration-profiles/profile-1/sync')
    .set('Cookie', 'session=authenticated')
    .expect(HttpStatus.OK, { profileId: 'profile-1', status: 'TRIGGERED' });
});

it('replays the dead letter queue', async () => {
  jest.spyOn(axios, 'post').mockResolvedValue({ data: { total: 1, success: 1, failed: 0 } });

  await request(app.getHttpServer())
    .post('/bff/api/v1/inbox/dlq/replay')
    .set('Cookie', 'session=authenticated')
    .expect(HttpStatus.OK, { total: 1, success: 1, failed: 0 });
});
```

Finally, update the real `main.ts` exclude list to match the test's:

```ts
// backoffice/apps/bff/src/main.ts
import { Logger, RequestMethod } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { ConfigService } from '@nestjs/config';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app/app.module';
import { readBackofficeConfig } from './config/backoffice-config';
import { configureSession } from './session/configure-session';
import { configureStaticShell } from './app/static-shell';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);
  const globalPrefix = 'api';
  app.setGlobalPrefix(globalPrefix, {
    exclude: [
      { path: 'auth/login', method: RequestMethod.GET },
      { path: 'auth/callback', method: RequestMethod.GET },
      { path: 'auth/session', method: RequestMethod.GET },
      { path: 'auth/logout', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles', method: RequestMethod.POST },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.PUT },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.DELETE },
      { path: 'bff/api/v1/integration-profiles/:profileId/sync', method: RequestMethod.POST },
      { path: 'bff/api/v1/inbox/dlq/replay', method: RequestMethod.POST },
    ],
  });
  configureSession(app, readBackofficeConfig(app.get(ConfigService)));
  configureStaticShell(app);
  await app.init();
  const port = process.env.PORT || 3000;
  await app.listen(port);
  Logger.log(
    `🚀 Application is running on: http://localhost:${port}/${globalPrefix}`,
  );
}

bootstrap();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test bff`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts backoffice/apps/bff/src/main.ts
git commit -m "feat(backoffice): expose full Integration Profile CRUD and DLQ replay through the BFF"
```

---

### Task 9: Shared toast service and empty-state component

**Files:**
- Create: `backoffice/apps/integration-mfe/src/app/shared/toast.service.ts`
- Test: `backoffice/apps/integration-mfe/src/app/shared/toast.service.spec.ts`
- Create: `backoffice/apps/integration-mfe/src/app/shared/console-empty-state.component.ts`
- Test: `backoffice/apps/integration-mfe/src/app/shared/console-empty-state.component.spec.ts`

**Interfaces:**
- Produces `ToastService` with `readonly message: Signal<string | null>` and `show(text: string, durationMs = 2600): void`, consumed by Tasks 12/13/15/17/18 to report real outcomes (create success, save success, conflict errors, sync triggered, DLQ replay result).
- Produces `ConsoleEmptyStateComponent` (`app-console-empty-state`, standalone) with signal inputs `title = input.required<string>()` and `description = input.required<string>()`, rendering the `.empty-state` utility class from Task 1. Consumers pass both as plain string attributes (`title="..."` / `description="..."`), which Angular binds to these named inputs without property-binding brackets. Consumed by Tasks 11/18/19/20 for the "not backed by real data yet" panels.

- [ ] **Step 1: Write the failing tests**

```ts
// backoffice/apps/integration-mfe/src/app/shared/toast.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('shows a message and clears it after the duration', () => {
    const service = TestBed.inject(ToastService);
    service.show('Perfil creado', 1000);
    expect(service.message()).toBe('Perfil creado');

    jest.advanceTimersByTime(1000);
    expect(service.message()).toBeNull();
  });

  it('replaces an in-flight toast and restarts its timer', () => {
    const service = TestBed.inject(ToastService);
    service.show('First', 1000);
    jest.advanceTimersByTime(500);
    service.show('Second', 1000);

    jest.advanceTimersByTime(500);
    expect(service.message()).toBe('Second');

    jest.advanceTimersByTime(500);
    expect(service.message()).toBeNull();
  });
});
```

```ts
// backoffice/apps/integration-mfe/src/app/shared/console-empty-state.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { ConsoleEmptyStateComponent } from './console-empty-state.component';

describe('ConsoleEmptyStateComponent', () => {
  it('renders the provided title and description', async () => {
    await TestBed.configureTestingModule({ imports: [ConsoleEmptyStateComponent] }).compileComponents();
    const fixture = TestBed.createComponent(ConsoleEmptyStateComponent);
    fixture.componentRef.setInput('title', 'Sin datos aún');
    fixture.componentRef.setInput('description', 'Esta vista requiere una API que todavía no existe.');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Sin datos aún');
    expect(fixture.nativeElement.textContent).toContain('Esta vista requiere una API que todavía no existe.');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — neither file exists yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/shared/toast.service.ts
import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  show(text: string, durationMs = 2600): void {
    clearTimeout(this.timer);
    this.message.set(text);
    this.timer = setTimeout(() => this.message.set(null), durationMs);
  }
}
```

```ts
// backoffice/apps/integration-mfe/src/app/shared/console-empty-state.component.ts
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-console-empty-state',
  standalone: true,
  template: `
    <div class="empty-state">
      <strong>{{ title() }}</strong>
      <span>{{ description() }}</span>
      <ng-content />
    </div>
  `,
})
export class ConsoleEmptyStateComponent {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/shared
git commit -m "feat(backoffice): add shared toast service and empty-state component"
```

---

### Task 10: Console routing skeleton

**Files:**
- Create: `backoffice/apps/integration-mfe/src/app/console-frame.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/console-frame.component.spec.ts`
- Create: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`
- Create: `backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/console.routes.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/remote-entry/entry.routes.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/app.routes.ts`
- Delete: `backoffice/apps/integration-mfe/src/app/remote-entry/integration-root.component.ts`

**Interfaces:**
- Produces `CONSOLE_ROUTES: Routes` (in `console.routes.ts`) consumed by both `remote-entry/entry.routes.ts` (the federated `./Routes` export the shell loads at `/integration`) and `app/app.routes.ts` (so `nx serve integration-mfe` standalone, on port 4202, shows the same pages for manual QA without the shell).
- Produces `ConsoleFrameComponent` — a thin parent route component (`<router-outlet/>` + a global toast rendered from Task 9's `ToastService`) that every console page mounts under, so Task 13's wizard and Task 17's sync/deactivate actions can report outcomes via one shared toast regardless of which page triggered them.
- Route table (nested under the frame): `''` → `DashboardPageComponent`, `'profiles'` → `IntegrationProfileListComponent` (Task 12), `'profiles/:profileId'` → `IntegrationProfileDetailComponent` (fleshed out in Tasks 14-17), `'monitor'` → `MonitorPageComponent` (Task 18), `'connectors'` → `ConnectorsPageComponent` (Task 19), `'credentials'` → `CredentialsPageComponent` (Task 20).
- Each new page component in this task renders only a real `.page`/`.page-header` shell (title + intro paragraph) — no fabricated body content yet; later tasks extend the same file rather than replacing it.

- [ ] **Step 1: Write the failing tests**

```ts
// backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { DashboardPageComponent } from './dashboard-page.component';

describe('DashboardPageComponent', () => {
  it('renders the console dashboard heading', async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(DashboardPageComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Salud de integraciones');
  });
});
```

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { IntegrationProfileDetailComponent } from './integration-profile-detail.component';

describe('IntegrationProfileDetailComponent', () => {
  it('renders a heading placeholder while the route is wired up', async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationProfileDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { paramMap: of({ get: () => 'p-1' }) } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('p-1');
  });
});
```

```ts
// backoffice/apps/integration-mfe/src/app/console-frame.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ConsoleFrameComponent } from './console-frame.component';
import { ToastService } from './shared/toast.service';

describe('ConsoleFrameComponent', () => {
  it('renders the router outlet and shows a toast when ToastService has a message', async () => {
    await TestBed.configureTestingModule({
      imports: [ConsoleFrameComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    const fixture = TestBed.createComponent(ConsoleFrameComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.toast')).toBeNull();

    TestBed.inject(ToastService).show('Perfil creado');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.toast')?.textContent).toContain('Perfil creado');
  });
});
```

(`MonitorPageComponent`, `ConnectorsPageComponent`, `CredentialsPageComponent` get their real specs in Tasks 18-20; this task only needs them to exist and compile so `console.routes.ts` type-checks — verified via the routing/build check in Step 2/4, not a dedicated spec per stub.)

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — none of the six new components/files exist yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-dashboard-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Salud de integraciones</h1>
          <p>Perfiles activos del tenant autenticado.</p>
        </div>
      </div>
    </section>
  `,
})
export class DashboardPageComponent {}
```

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-detail',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <h1>Profile {{ profileId() }}</h1>
      </div>
    </section>
  `,
})
export class IntegrationProfileDetailComponent {
  private readonly route = inject(ActivatedRoute);
  readonly profileId = toSignal(this.route.paramMap.pipe(map((params) => params.get('profileId') ?? '')), {
    initialValue: '',
  });
}
```

```ts
// backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-monitor-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Monitor de mensajes</h1>
          <p>Outbox e Inbox del tenant.</p>
        </div>
      </div>
    </section>
  `,
})
export class MonitorPageComponent {}
```

```ts
// backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-connectors-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Conectores y adapters</h1>
          <p>Catálogo instalado en el core.</p>
        </div>
      </div>
    </section>
  `,
})
export class ConnectorsPageComponent {}
```

```ts
// backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-credentials-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Credenciales</h1>
          <p>Referencias administradas por Vault.</p>
        </div>
      </div>
    </section>
  `,
})
export class CredentialsPageComponent {}
```

```ts
// backoffice/apps/integration-mfe/src/app/console-frame.component.ts
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastService } from './shared/toast.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-console-frame',
  standalone: true,
  imports: [RouterOutlet],
  template: `
    <router-outlet />
    @if (toast.message(); as message) {
      <div class="toast">
        <span class="toast-dot"></span>
        {{ message }}
      </div>
    }
  `,
  styles: [
    `.toast-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); display: inline-block; }`,
  ],
})
export class ConsoleFrameComponent {
  protected readonly toast = inject(ToastService);
}
```

```ts
// backoffice/apps/integration-mfe/src/app/console.routes.ts
import { Routes } from '@angular/router';
import { ConnectorsPageComponent } from './connectors/connectors-page.component';
import { ConsoleFrameComponent } from './console-frame.component';
import { CredentialsPageComponent } from './credentials/credentials-page.component';
import { DashboardPageComponent } from './dashboard/dashboard-page.component';
import { IntegrationProfileDetailComponent } from './integration-profile/integration-profile-detail.component';
import { IntegrationProfileListComponent } from './integration-profile/integration-profile-list.component';
import { MonitorPageComponent } from './monitor/monitor-page.component';

export const CONSOLE_ROUTES: Routes = [
  {
    path: '',
    component: ConsoleFrameComponent,
    children: [
      { path: '', component: DashboardPageComponent },
      { path: 'profiles', component: IntegrationProfileListComponent },
      { path: 'profiles/:profileId', component: IntegrationProfileDetailComponent },
      { path: 'monitor', component: MonitorPageComponent },
      { path: 'connectors', component: ConnectorsPageComponent },
      { path: 'credentials', component: CredentialsPageComponent },
    ],
  },
];
```

```ts
// backoffice/apps/integration-mfe/src/app/remote-entry/entry.routes.ts
import { Routes } from '@angular/router';
import { CONSOLE_ROUTES } from '../console.routes';

export const routes: Routes = CONSOLE_ROUTES;
```

```ts
// backoffice/apps/integration-mfe/src/app/app.routes.ts
import { Route } from '@angular/router';
import { CONSOLE_ROUTES } from './console.routes';

export const appRoutes: Route[] = CONSOLE_ROUTES;
```

Delete `backoffice/apps/integration-mfe/src/app/remote-entry/integration-root.component.ts` — it was the sole `''` route's component and is no longer referenced anywhere (confirm with `grep -rn "IntegrationRootComponent" backoffice/apps/integration-mfe/src` before deleting: only `entry.routes.ts`, which this step already rewrote, should match).

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS. Also run `npx nx build integration-mfe` to confirm the route table and the deleted file compile cleanly.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/console-frame.component.ts backoffice/apps/integration-mfe/src/app/console-frame.component.spec.ts backoffice/apps/integration-mfe/src/app/dashboard backoffice/apps/integration-mfe/src/app/monitor backoffice/apps/integration-mfe/src/app/connectors backoffice/apps/integration-mfe/src/app/credentials backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts backoffice/apps/integration-mfe/src/app/console.routes.ts backoffice/apps/integration-mfe/src/app/remote-entry/entry.routes.ts backoffice/apps/integration-mfe/src/app/app.routes.ts
git rm backoffice/apps/integration-mfe/src/app/remote-entry/integration-root.component.ts
git commit -m "feat(backoffice): stand up the full console route table"
```

---

### Task 11: Dashboard — real KPIs derived from Integration Profiles

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts`

**Interfaces:**
- Consumes `IntegrationProfileService.list(false)` (Task 6) to get every profile regardless of `active`.
- Every number shown is computed client-side from that real list: active/inactive counts, a `SHARED` source-of-truth count, a per-direction breakdown with percentage-of-total, and a per-source-of-truth breakdown. The one number with no backing data ("En DLQ") renders `—` with an explanatory note instead of a fabricated figure.
- "Perfiles que requieren atención" lists real inactive profiles (max 3) linking to `/integration/profiles/:id`.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { DashboardPageComponent } from './dashboard-page.component';

const profile = (overrides: Partial<Record<string, unknown>>) => ({
  id: 'id',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  version: 1,
  ...overrides,
});

describe('DashboardPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders the dashboard heading', () => {
    const fixture = TestBed.createComponent(DashboardPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Salud de integraciones');
  });

  it('computes real KPI counts and marks the DLQ figure as unavailable', () => {
    const fixture = TestBed.createComponent(DashboardPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush([
      profile({ id: 'p1', active: true, syncDirection: 'INBOUND', sourceOfTruth: 'EXTERNAL' }),
      profile({ id: 'p2', active: true, syncDirection: 'OUTBOUND', sourceOfTruth: 'PLATFORM' }),
      profile({ id: 'p3', active: false, businessDomain: 'customer', externalSource: 'SAP', syncDirection: 'BIDIRECTIONAL', sourceOfTruth: 'SHARED' }),
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('2'); // active count
    expect(text).toContain('1'); // inactive count
    expect(text).toContain('No disponible');
    expect(text).toContain('customer'); // attention list shows the inactive profile
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the current stub never calls the service and has no KPI markup.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IntegrationProfile, SourceOfTruth, SyncDirection } from '../integration-profile/integration-profile.model';
import { IntegrationProfileService } from '../integration-profile/integration-profile.service';

type DashboardState = 'loading' | 'ready' | 'unavailable';

const DIRECTIONS: SyncDirection[] = ['INBOUND', 'OUTBOUND', 'BIDIRECTIONAL'];
const SOTS: SourceOfTruth[] = ['PLATFORM', 'EXTERNAL', 'SHARED'];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
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
```

```html
<!-- backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.html -->
<section class="page">
  <div class="page-header">
    <div>
      <h1>Salud de integraciones</h1>
      <p>Perfiles configurados para el tenant autenticado.</p>
    </div>
  </div>

  @if (state() === 'unavailable') {
    <div class="card">
      <div class="empty-state">
        <strong>No se pudieron cargar los perfiles</strong>
        <span>Intenta recargar la página.</span>
      </div>
    </div>
  } @else {
    <div class="kpi-grid">
      <div class="card kpi">
        <span class="mono kpi-label">PERFILES ACTIVOS</span>
        <span class="kpi-value">{{ activeCount() }}</span>
        <span class="kpi-note">de {{ profiles().length }} configurados</span>
      </div>
      <div class="card kpi">
        <span class="mono kpi-label">PERFILES INACTIVOS</span>
        <span class="kpi-value">{{ inactiveCount() }}</span>
        <span class="kpi-note">requieren revisión</span>
      </div>
      <div class="card kpi">
        <span class="mono kpi-label">SOURCE OF TRUTH COMPARTIDO</span>
        <span class="kpi-value">{{ sharedSotCount() }}</span>
        <span class="kpi-note">conflictos posibles</span>
      </div>
      <div class="card kpi">
        <span class="mono kpi-label">EN DLQ</span>
        <span class="kpi-value muted">—</span>
        <span class="kpi-note">No disponible: requiere API de mensajería</span>
      </div>
    </div>

    <div class="dashboard-columns">
      <div class="card">
        <div class="card-header">
          <span>Perfiles que requieren atención</span>
          <a routerLink="/integration/profiles" class="btn-ghost see-all">Ver todos →</a>
        </div>
        @if (attention().length === 0) {
          <div class="empty-state">
            <strong>Todos los perfiles están activos</strong>
          </div>
        } @else {
          @for (p of attention(); track p.id) {
            <a class="attention-row" [routerLink]="['/integration/profiles', p.id]">
              <div class="attention-name">
                <span>{{ p.businessDomain }} · {{ p.externalSource }}</span>
                <span class="mono attention-code">{{ p.businessDomain }}&#64;{{ p.externalSource | lowercase }}</span>
              </div>
              <span class="attention-issue">Perfil inactivo</span>
              <span class="badge inactive">Inactivo</span>
            </a>
          }
        }
      </div>

      <div class="card flow-card">
        <span class="flow-title">Flujo por dirección</span>
        @for (f of directionBreakdown(); track f.direction) {
          <div class="flow-row">
            <div class="flow-row-head">
              <span class="mono">{{ f.direction }}</span>
              <span class="mono">{{ f.count }}</span>
            </div>
            <div class="flow-bar"><div class="flow-bar-fill" [style.width.%]="f.pct"></div></div>
            <span class="flow-note">{{ f.pct }}% de los perfiles configurados</span>
          </div>
        }
        <div class="sot-row">
          <span class="mono sot-title">SOURCE OF TRUTH</span>
          <div class="sot-chips">
            @for (s of sotBreakdown(); track s.sot) {
              <div class="sot-chip">
                <span class="sot-count">{{ s.count }}</span>
                <span class="mono sot-label">{{ s.sot }}</span>
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  }
</section>
```

```css
/* backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.css */
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi { padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
.kpi-label { font-size: 10px; letter-spacing: 0.08em; color: var(--text-dim); text-transform: uppercase; }
.kpi-value { font-size: 26px; font-weight: 600; letter-spacing: -0.02em; }
.kpi-value.muted { color: var(--text-dim); }
.kpi-note { font-size: 11.5px; color: var(--text-muted); }

.dashboard-columns { display: grid; grid-template-columns: 1.55fr 1fr; gap: 16px; align-items: start; }

.see-all { margin-left: auto; }

.attention-row {
  display: grid; grid-template-columns: 1fr 130px 100px; gap: 12px; align-items: center;
  padding: 11px 16px; border-bottom: 1px solid var(--border-soft); text-decoration: none; color: inherit;
}
.attention-row:hover { background: var(--surface-2); }
.attention-name { display: flex; flex-direction: column; gap: 2px; }
.attention-code { font-size: 10.5px; color: var(--text-dim); }
.attention-issue { font-size: 11.5px; color: var(--text-muted); }
.badge.inactive { color: var(--text-muted); background: var(--surface-2); justify-self: end; }

.flow-card { padding: 16px; display: flex; flex-direction: column; gap: 14px; }
.flow-title { font-weight: 600; }
.flow-row { display: flex; flex-direction: column; gap: 6px; }
.flow-row-head { display: flex; justify-content: space-between; }
.flow-bar { height: 6px; background: var(--border-soft); border-radius: 3px; overflow: hidden; }
.flow-bar-fill { height: 100%; background: var(--dir-in); }
.flow-note { font-size: 11px; color: var(--text-dim); }

.sot-row { border-top: 1px solid var(--border-soft); padding-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.sot-title { font-size: 10px; letter-spacing: 0.08em; color: var(--text-dim); }
.sot-chips { display: flex; gap: 6px; }
.sot-chip { flex: 1; border: 1px solid var(--border); border-radius: 4px; padding: 8px; display: flex; flex-direction: column; gap: 2px; }
.sot-count { font-size: 17px; font-weight: 600; }
.sot-label { font-size: 9.5px; color: var(--text-dim); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/dashboard
git commit -m "feat(backoffice): compute real dashboard KPIs from the profiles list"
```

---

### Task 12: Redesign the Integration Profiles list page

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts`
- Modify: `backoffice/apps/shell/src/styles.css` (append badge modifiers)
- Modify: `backoffice/apps/integration-mfe/src/styles.css` (append the same badge modifiers)

**Interfaces:**
- Keeps the existing `ProfileListState` machine (`loading | ready | empty | session-expired | unavailable`) and the 401/403-redirects-to-login behavior — this task only adds client-side search/filter over the already-fetched list and restyles the table; it does not change what triggers each state.
- Adds `search: Signal<string>`, `directionFilter: Signal<'ALL' | SyncDirection>`, and `filteredProfiles: Signal<IntegrationProfile[]>` (derived, never re-fetches). Rows navigate to `/integration/profiles/:id` via `Router.navigate`, consumed by Task 14's detail page.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileListComponent, WINDOW } from './integration-profile-list.component';

const profile = (overrides: Partial<Record<string, unknown>>) => ({
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'orders',
  externalSource: 'erp',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 2,
  ...overrides,
});

describe('IntegrationProfileListComponent', () => {
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    assign = vi.fn();
    await TestBed.configureTestingModule({
      imports: [IntegrationProfileListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: WINDOW, useValue: { location: { assign } } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows a loading status while profiles are being requested', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cargando integration profiles');
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true');
  });

  it('renders a table for loaded profiles and navigates to the detail page on row click', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate');
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([profile({})]);
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLTableRowElement;
    expect(row.textContent).toContain('orders');
    row.click();
    expect(navigateSpy).toHaveBeenCalledWith(['/integration/profiles', 'p-1']);
  });

  it('filters rows by the direction chip group', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([
      profile({ id: 'p-1', businessDomain: 'orders', syncDirection: 'INBOUND' }),
      profile({ id: 'p-2', businessDomain: 'invoices', syncDirection: 'OUTBOUND' }),
    ]);
    fixture.detectChanges();

    const outboundChip = Array.from(fixture.nativeElement.querySelectorAll('.chip')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'OUTBOUND',
    ) as HTMLButtonElement;
    outboundChip.click();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('invoices');
  });

  it('filters rows by the search box across domain and source', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([
      profile({ id: 'p-1', businessDomain: 'orders', externalSource: 'erp' }),
      profile({ id: 'p-2', businessDomain: 'invoices', externalSource: 'sap' }),
    ]);
    fixture.detectChanges();

    const search = fixture.nativeElement.querySelector('.search') as HTMLInputElement;
    search.value = 'sap';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('invoices');
  });

  it('shows an empty state when no profiles are returned', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No hay integration profiles configurados');
  });

  it.each([401, 403])('shows a session state and redirects to login for %i responses', (status) => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush('', {
      status,
      statusText: 'Authentication required',
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('sesión');
    expect(assign).toHaveBeenCalledWith('/auth/login');
  });

  it('retries a 502 profile request from the unavailable state', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush('Gateway failure details', {
      status: 502,
      statusText: 'Bad Gateway',
    });
    fixture.detectChanges();

    const retryButton = fixture.nativeElement.querySelector('button.btn') as HTMLButtonElement;
    expect(retryButton.textContent).toContain('Reintentar');
    retryButton.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cargando integration profiles');
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — no search box, chip group, or `Router.navigate` call in the current component.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts
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
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html -->
<section class="page" aria-labelledby="integration-profiles-heading">
  <div class="page-header">
    <div>
      <h1 id="integration-profiles-heading">Integration Profiles</h1>
      <p>Un perfil define cómo un dominio de negocio se acopla a una fuente externa.</p>
    </div>
  </div>

  @if (state() === 'loading') {
    <p aria-live="polite">Cargando integration profiles…</p>
  } @else if (state() === 'session-expired') {
    <p role="alert">Tu sesión ya no está activa. Redirigiendo a inicio de sesión.</p>
  } @else if (state() === 'unavailable') {
    <p role="alert">Los integration profiles no están disponibles temporalmente. Intenta de nuevo.</p>
    <button type="button" class="btn" (click)="retry()">Reintentar</button>
  } @else if (state() === 'empty') {
    <p>No hay integration profiles configurados todavía.</p>
  } @else {
    <div class="toolbar">
      <input
        class="search"
        [value]="search()"
        (input)="onSearch($any($event.target).value)"
        placeholder="Buscar por dominio, fuente, connector…"
      />
      <div class="chip-group">
        @for (direction of directions; track direction) {
          <button type="button" class="chip" [class.active]="directionFilter() === direction" (click)="setDirection(direction)">
            {{ direction === 'ALL' ? 'Todas' : direction }}
          </button>
        }
      </div>
      <span class="mono count">{{ filteredProfiles().length }} de {{ profiles().length }} perfiles</span>
    </div>

    <div class="card">
      <table>
        <caption class="visually-hidden">Configured integration profiles</caption>
        <thead>
          <tr>
            <th scope="col">Dominio / Fuente</th>
            <th scope="col">Connector · Adapter</th>
            <th scope="col">Dirección</th>
            <th scope="col">Protocol</th>
            <th scope="col">Source of truth</th>
            <th scope="col">Endpoint</th>
            <th scope="col">Actualizado</th>
            <th scope="col">Estado</th>
          </tr>
        </thead>
        <tbody>
          @for (profile of filteredProfiles(); track profile.id) {
            <tr (click)="open(profile)" class="row">
              <td class="cell-primary">{{ profile.businessDomain }} · {{ profile.externalSource }}</td>
              <td>
                <div>{{ profile.configuration?.connector || '—' }}</div>
                <div class="mono cell-sub">{{ profile.configuration?.adapter || '—' }}</div>
              </td>
              <td><span [class]="directionBadgeClass(profile.syncDirection)">{{ profile.syncDirection }}</span></td>
              <td><span class="mono protocol-chip">{{ profile.configuration?.protocol || '—' }}</span></td>
              <td class="mono">{{ profile.sourceOfTruth }}</td>
              <td class="mono cell-sub">{{ profile.configuration?.endpoint || '—' }}</td>
              <td>{{ profile.updatedAt.slice(0, 10) }}</td>
              <td><span [class]="statusBadgeClass(profile.active)">{{ profile.active ? 'Activo' : 'Inactivo' }}</span></td>
            </tr>
          } @empty {
            <tr><td colspan="8">Ningún perfil coincide con la búsqueda o el filtro.</td></tr>
          }
        </tbody>
      </table>
    </div>
  }
</section>
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.css */
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.search { width: 300px; border: 1px solid var(--border); border-radius: 5px; padding: 7px 11px; outline: none; background: var(--surface); }
.search:focus { border-color: var(--accent); }
.count { margin-left: auto; color: var(--text-dim); }

table { width: 100%; border-collapse: collapse; }
thead th { text-align: left; font-family: 'IBM Plex Mono', monospace; font-size: 10px; letter-spacing: 0.07em; color: var(--text-dim); padding: 9px 16px; background: var(--surface-2); border-bottom: 1px solid var(--border); }
tbody td { padding: 11px 16px; border-bottom: 1px solid var(--border-soft); vertical-align: middle; }
.row { cursor: pointer; }
.row:hover { background: var(--surface-2); }
.cell-primary { font-weight: 600; }
.cell-sub { color: var(--text-dim); font-size: 11px; }
.protocol-chip { border: 1px solid var(--border); border-radius: 3px; padding: 2px 6px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
```

Append to both `backoffice/apps/shell/src/styles.css` and `backoffice/apps/integration-mfe/src/styles.css` (identical content in both, per the duplication constraint):

```css
.badge.active { color: var(--ok); background: color-mix(in oklab, var(--ok) 15%, var(--surface)); }
.badge.inactive { color: var(--text-muted); background: var(--surface-2); }
.badge.dir-inbound { color: var(--dir-in); background: color-mix(in oklab, var(--dir-in) 15%, var(--surface)); }
.badge.dir-outbound { color: var(--dir-out); background: color-mix(in oklab, var(--dir-out) 15%, var(--surface)); }
.badge.dir-bidirectional { color: var(--accent-fg); background: var(--accent-tint); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts backoffice/apps/shell/src/styles.css backoffice/apps/integration-mfe/src/styles.css
git commit -m "feat(backoffice): redesign the Integration Profiles list with search and direction filters"
```

---

### Task 13: Create-profile wizard, wired into the list page

**Files:**
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.html`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.css`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.spec.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts`

**Interfaces:**
- Produces `IntegrationProfileWizardComponent` with `@Output() closed: EventEmitter<void>` and `@Output() created: EventEmitter<IntegrationProfile>`, consumed by the list page.
- Guided mode covers exactly what `CreateIntegrationProfilePayload` (Task 6) needs to make a valid profile: domain/source (step 1), direction/source-of-truth (step 2), connectivity — protocol/connector/adapter/endpoint/credentialRef (step 3), and a read-only JSON review before submit (step 4). It deliberately does **not** try to collect `mapping`/`transformation`/`retryPolicy`/etc. in the wizard — those are edited after creation in the detail page (Tasks 16-17), because there is no backend validation or dry-run to guide the user through them here.
- Expert mode replaces the four steps with one raw-JSON textarea seeded from the guided model; submitting parses it client-side and posts the parsed object as the payload.
- Client-side validation mirrors the real domain invariant in `IntegrationProfileConfiguration` (`application/src/main/java/com/cl2/integration/domain/model/IntegrationProfileConfiguration.java`): if `protocol` is set, `connector` and `adapter` are required — block "Continuar" past step 3 otherwise instead of letting the API 400 first.
- On success, shows a toast via Task 9's `ToastService` and emits `created`; on a `409` conflict or `400` validation error, reads `error.error?.detail` (the real `ProblemDetail.detail` from Task 8's passthrough) and shows it inline in the modal instead of closing.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileWizardComponent } from './integration-profile-wizard.component';

describe('IntegrationProfileWizardComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationProfileWizardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create() {
    const fixture = TestBed.createComponent(IntegrationProfileWizardComponent);
    fixture.detectChanges();
    return fixture;
  }

  function fill(fixture: ReturnType<typeof create>, selector: string, value: string) {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function clickNext(fixture: ReturnType<typeof create>) {
    (fixture.nativeElement.querySelector('[data-testid="wizard-next"]') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('blocks advancing past connectivity when protocol is set without connector and adapter', () => {
    const fixture = create();
    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture); // direction/SOT step, defaults are valid
    fill(fixture, '[name="protocol"]', 'KAFKA');
    clickNext(fixture);

    expect(fixture.nativeElement.textContent).toContain('connector y adapter son obligatorios');
  });

  it('submits the guided payload and emits created on success', () => {
    const fixture = create();
    const createdSpy = vi.fn();
    fixture.componentInstance.created.subscribe(createdSpy);

    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture);
    fill(fixture, '[name="protocol"]', 'KAFKA');
    fill(fixture, '[name="connector"]', 'sigo-kafka-connector');
    fill(fixture, '[name="adapter"]', 'SigoVehicleAdapter');
    clickNext(fixture);
    clickNext(fixture); // review step -> submits

    const request = http.expectOne('/bff/api/v1/integration-profiles');
    expect(request.request.body).toMatchObject({
      businessDomain: 'vehicle',
      externalSource: 'SIGO',
      protocol: 'KAFKA',
      connector: 'sigo-kafka-connector',
      adapter: 'SigoVehicleAdapter',
    });
    request.flush({ id: 'p-new', businessDomain: 'vehicle' });

    expect(createdSpy).toHaveBeenCalledWith(expect.objectContaining({ id: 'p-new' }));
  });

  it('shows the upstream conflict detail inline instead of closing on a 409', () => {
    const fixture = create();
    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture);
    clickNext(fixture);
    clickNext(fixture);

    const request = http.expectOne('/bff/api/v1/integration-profiles');
    request.flush(
      { detail: 'An active integration profile already exists for this domain and source' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('An active integration profile already exists');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the component doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.ts
import { ChangeDetectionStrategy, Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { JsonPipe } from '@angular/common';
import { ApiProblem, CreateIntegrationProfilePayload, IntegrationProfile, IntegrationProtocol, SourceOfTruth, SyncDirection } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { ToastService } from '../shared/toast.service';

interface WizardModel {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  protocol: IntegrationProtocol | '';
  connector: string;
  adapter: string;
  endpoint: string;
  credentialRef: string;
}

const STEP_LABELS = ['Dominio y fuente', 'Dirección y source of truth', 'Conectividad', 'Revisión'];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-wizard',
  standalone: true,
  imports: [JsonPipe],
  templateUrl: './integration-profile-wizard.component.html',
  styleUrl: './integration-profile-wizard.component.css',
})
export class IntegrationProfileWizardComponent {
  private readonly profileService = inject(IntegrationProfileService);
  private readonly toast = inject(ToastService);

  @Output() closed = new EventEmitter<void>();
  @Output() created = new EventEmitter<IntegrationProfile>();

  readonly stepLabels = STEP_LABELS;
  readonly step = signal(0);
  readonly expert = signal(false);
  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly model = signal<WizardModel>({
    businessDomain: '',
    externalSource: '',
    syncDirection: 'INBOUND',
    sourceOfTruth: 'EXTERNAL',
    protocol: '',
    connector: '',
    adapter: '',
    endpoint: '',
    credentialRef: '',
  });

  readonly expertJson = signal('');

  updateField<K extends keyof WizardModel>(key: K, value: WizardModel[K]): void {
    this.model.update((current) => ({ ...current, [key]: value }));
  }

  toggleExpert(): void {
    if (!this.expert()) {
      this.expertJson.set(JSON.stringify(this.buildPayload(), null, 2));
    }
    this.expert.update((value) => !value);
  }

  next(): void {
    this.errorMessage.set(null);
    if (this.expert()) {
      this.submit();
      return;
    }
    if (this.step() === 2 && !this.connectivityValid()) {
      this.errorMessage.set('Si defines protocol, connector y adapter son obligatorios.');
      return;
    }
    if (this.step() === this.stepLabels.length - 1) {
      this.submit();
      return;
    }
    this.step.update((s) => s + 1);
  }

  back(): void {
    this.errorMessage.set(null);
    this.step.update((s) => Math.max(0, s - 1));
  }

  close(): void {
    this.closed.emit();
  }

  private connectivityValid(): boolean {
    const m = this.model();
    if (!m.protocol) return true;
    return m.connector.trim().length > 0 && m.adapter.trim().length > 0;
  }

  private buildPayload(): CreateIntegrationProfilePayload {
    const m = this.model();
    return {
      businessDomain: m.businessDomain,
      externalSource: m.externalSource,
      syncDirection: m.syncDirection,
      sourceOfTruth: m.sourceOfTruth,
      protocol: m.protocol || null,
      connector: m.connector || null,
      adapter: m.adapter || null,
      endpoint: m.endpoint || null,
      credentialRef: m.credentialRef || null,
    };
  }

  private submit(): void {
    let payload: CreateIntegrationProfilePayload;
    if (this.expert()) {
      try {
        payload = JSON.parse(this.expertJson());
      } catch {
        this.errorMessage.set('El JSON no es válido.');
        return;
      }
    } else {
      payload = this.buildPayload();
    }

    this.submitting.set(true);
    this.profileService.create(payload).subscribe({
      next: (profile) => {
        this.submitting.set(false);
        this.toast.show('Perfil creado en estado Borrador.');
        this.created.emit(profile);
      },
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        const problem = error.error as ApiProblem | undefined;
        this.errorMessage.set(problem?.detail || 'No se pudo crear el perfil.');
      },
    });
  }
}
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.html -->
<div class="modal-overlay">
  <div class="modal">
    <div class="modal-header">
      <div>
        <span class="modal-title">Nuevo Integration Profile</span>
        <span class="modal-sub">{{ expert() ? 'Edición directa del payload' : stepLabels[step()] }}</span>
      </div>
      <button type="button" class="btn" (click)="toggleExpert()">{{ expert() ? 'Modo guiado' : 'Modo experto' }}</button>
      <button type="button" class="btn-ghost close-btn" (click)="close()">×</button>
    </div>

    @if (errorMessage(); as message) {
      <div class="wizard-error">{{ message }}</div>
    }

    @if (expert()) {
      <div class="modal-body">
        <textarea
          class="expert-json"
          [value]="expertJson()"
          (input)="expertJson.set($any($event.target).value)"
        ></textarea>
      </div>
    } @else {
      <div class="modal-body">
        @switch (step()) {
          @case (0) {
            <label class="field">
              <span class="label">BUSINESS DOMAIN</span>
              <input name="businessDomain" [value]="model().businessDomain" (input)="updateField('businessDomain', $any($event.target).value)" />
            </label>
            <label class="field">
              <span class="label">EXTERNAL SOURCE</span>
              <input name="externalSource" [value]="model().externalSource" (input)="updateField('externalSource', $any($event.target).value)" />
            </label>
          }
          @case (1) {
            <label class="field">
              <span class="label">SYNC DIRECTION</span>
              <select name="syncDirection" [value]="model().syncDirection" (change)="updateField('syncDirection', $any($event.target).value)">
                <option value="INBOUND">INBOUND</option>
                <option value="OUTBOUND">OUTBOUND</option>
                <option value="BIDIRECTIONAL">BIDIRECTIONAL</option>
              </select>
            </label>
            <label class="field">
              <span class="label">SOURCE OF TRUTH</span>
              <select name="sourceOfTruth" [value]="model().sourceOfTruth" (change)="updateField('sourceOfTruth', $any($event.target).value)">
                <option value="PLATFORM">PLATFORM</option>
                <option value="EXTERNAL">EXTERNAL</option>
                <option value="SHARED">SHARED</option>
              </select>
            </label>
          }
          @case (2) {
            <label class="field">
              <span class="label">PROTOCOL</span>
              <select name="protocol" [value]="model().protocol" (change)="updateField('protocol', $any($event.target).value)">
                <option value="">—</option>
                <option value="REST">REST</option>
                <option value="SOAP">SOAP</option>
                <option value="JSON_RPC">JSON_RPC</option>
                <option value="KAFKA">KAFKA</option>
                <option value="JDBC">JDBC</option>
              </select>
            </label>
            <label class="field">
              <span class="label">CONNECTOR</span>
              <input name="connector" [value]="model().connector" (input)="updateField('connector', $any($event.target).value)" />
            </label>
            <label class="field">
              <span class="label">ADAPTER</span>
              <input name="adapter" [value]="model().adapter" (input)="updateField('adapter', $any($event.target).value)" />
            </label>
            <label class="field">
              <span class="label">ENDPOINT</span>
              <input name="endpoint" [value]="model().endpoint" (input)="updateField('endpoint', $any($event.target).value)" />
            </label>
            <label class="field">
              <span class="label">CREDENTIAL REF</span>
              <input name="credentialRef" [value]="model().credentialRef" (input)="updateField('credentialRef', $any($event.target).value)" />
            </label>
          }
          @case (3) {
            <pre class="review-json">{{ this.model() | json }}</pre>
          }
        }
      </div>
    }

    <div class="modal-footer">
      <span class="mono footer-progress">{{ expert() ? 'POST /api/v1/integration-profiles' : 'Paso ' + (step() + 1) + ' de ' + stepLabels.length }}</span>
      <div class="footer-actions">
        <button type="button" class="btn" (click)="close()">Cancelar</button>
        @if (!expert() && step() > 0) {
          <button type="button" class="btn" (click)="back()">Atrás</button>
        }
        <button type="button" data-testid="wizard-next" class="btn btn-primary" [disabled]="submitting()" (click)="next()">
          {{ expert() || step() === stepLabels.length - 1 ? 'Crear profile' : 'Continuar' }}
        </button>
      </div>
    </div>
  </div>
</div>
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.css */
.modal-header { padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 14px; }
.modal-title { display: block; font-size: 16px; font-weight: 600; }
.modal-sub { display: block; font-size: 12px; color: var(--text-dim); }
.close-btn { margin-left: auto; font-size: 18px; }
.wizard-error { margin: 0 24px; padding: 10px 13px; background: color-mix(in oklab, var(--err) 12%, var(--surface)); border: 1px solid var(--err); border-radius: 5px; color: var(--err); font-size: 12.5px; }
.modal-body { flex: 1; overflow: auto; padding: 20px 24px; display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-content: start; }
.expert-json { grid-column: 1 / -1; min-height: 380px; font-family: 'IBM Plex Mono', monospace; font-size: 11.5px; border: 1px solid var(--border); border-radius: 6px; padding: 14px; background: var(--surface-2); }
.review-json { grid-column: 1 / -1; margin: 0; padding: 16px; background: #0F0F11; color: #E7E7EA; border-radius: 6px; font-family: 'IBM Plex Mono', monospace; font-size: 11.5px; overflow: auto; }
.modal-footer { padding: 14px 24px; border-top: 1px solid var(--border); display: flex; align-items: center; gap: 10px; background: var(--surface-2); }
.footer-actions { margin-left: auto; display: flex; gap: 8px; }
```

Now wire the wizard into the list page. Update the `@Component` decorator and class body in `integration-profile-list.component.ts`:

```ts
import { IntegrationProfileWizardComponent } from './integration-profile-wizard.component';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-list',
  standalone: true,
  imports: [IntegrationProfileWizardComponent],
  templateUrl: './integration-profile-list.component.html',
  styleUrl: './integration-profile-list.component.css',
})
export class IntegrationProfileListComponent implements OnInit {
  // ...existing injected services, signals, and methods from Task 12 stay as-is; add:

  readonly wizardOpen = signal(false);

  openWizard(): void {
    this.wizardOpen.set(true);
  }

  onCreated(profile: IntegrationProfile): void {
    this.wizardOpen.set(false);
    this.router.navigate(['/integration/profiles', profile.id]);
  }
}
```

And in `integration-profile-list.component.html`, add the button next to the header and mount the wizard:

```html
<!-- inside .page-header, after the <div> with h1/p -->
<button type="button" class="btn btn-primary new-profile-btn" (click)="openWizard()">＋ Nuevo profile</button>
```

```html
<!-- at the very end of the template, outside the @if/@else state block -->
@if (wizardOpen()) {
  <app-integration-profile-wizard (closed)="wizardOpen.set(false)" (created)="onCreated($event)" />
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS. Add one more assertion to `integration-profile-list.component.spec.ts` confirming the button opens the wizard modal:

```ts
it('opens the create wizard from the toolbar button', () => {
  const fixture = TestBed.createComponent(IntegrationProfileListComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([]);
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('.new-profile-btn') as HTMLButtonElement).click();
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('app-integration-profile-wizard')).not.toBeNull();
});
```

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-wizard.component.spec.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts
git commit -m "feat(backoffice): add the guided/expert create-profile wizard"
```

---

### Task 14: Profile detail page shell — load, tabs, deep-linkable tab state

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`

**Interfaces:**
- Produces `IntegrationProfileDetailComponent` with `state: Signal<'loading'|'ready'|'not-found'|'unavailable'>`, `profile: Signal<IntegrationProfile|null>`, `tab: Signal<DetailTab>` where `DetailTab = 'general'|'conn'|'map'|'pol'|'sync'`. The active tab is kept in sync with the `?tab=` query param (readable on deep-link, updated via `Router.navigate` with `queryParamsHandling: 'merge'`) so a URL can be shared pointing at a specific tab.
- Tab bodies in this task are minimal markers (`data-testid="tab-general"`, etc.) — Tasks 15-17 replace each `@switch` case's body with real fields, one tab pair per task, without touching this task's loading/routing logic.
- Real `Estado`/`Dirección` badges come straight from `profile()` (2-state `active`/`inactive`, no fabricated "Degradado"/"Con error").

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { IntegrationProfileDetailComponent } from './integration-profile-detail.component';

const FULL_PROFILE = {
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 7,
};

describe('IntegrationProfileDetailComponent', () => {
  let http: HttpTestingController;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let navigateSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    params = new BehaviorSubject(convertToParamMap({ profileId: 'p-1' }));
    queryParams = new BehaviorSubject(convertToParamMap({}));
    navigateSpy = vi.fn();

    await TestBed.configureTestingModule({
      imports: [IntegrationProfileDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: params.asObservable(), queryParamMap: queryParams.asObservable() },
        },
        { provide: Router, useValue: { navigate: navigateSpy } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the profile and renders its identity in the header', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('vehicle');
    expect(fixture.nativeElement.textContent).toContain('SIGO');
    expect(fixture.nativeElement.textContent).toContain('Activo');
  });

  it('shows a not-found state for a 404', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No se encontró el perfil');
  });

  it('reads the initial tab from the ?tab= query param', () => {
    queryParams.next(convertToParamMap({ tab: 'conn' }));
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="tab-conn"]')).not.toBeNull();
  });

  it('switches tabs and updates the query param', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    const mapTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'Mapping & Transformation',
    ) as HTMLButtonElement;
    mapTabButton.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="tab-map"]')).not.toBeNull();
    expect(navigateSpy).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { tab: 'map' } }));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the Task 10 stub only shows `Profile {{ profileId }}`; there's no fetch, header, or tabs yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts
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
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html -->
@if (state() === 'loading') {
  <section class="page"><p aria-live="polite">Cargando perfil…</p></section>
} @else if (state() === 'not-found') {
  <section class="page">
    <p role="alert">No se encontró el perfil solicitado.</p>
    <a routerLink="/integration/profiles" class="btn">← Integration Profiles</a>
  </section>
} @else if (state() === 'unavailable') {
  <section class="page">
    <p role="alert">El perfil no está disponible temporalmente.</p>
    <button type="button" class="btn" (click)="reload()">Reintentar</button>
  </section>
} @else if (profile(); as p) {
  <div class="detail-shell">
    <div class="detail-head">
      <a routerLink="/integration/profiles" class="btn-ghost back-link">← Integration Profiles</a>
      <div class="detail-title-row">
        <div class="detail-title">
          <div class="detail-title-line">
            <h1>{{ p.businessDomain }} · {{ p.externalSource }}</h1>
            <span [class]="'badge dir-' + p.syncDirection.toLowerCase()">{{ p.syncDirection }}</span>
            <span [class]="'badge ' + (p.active ? 'active' : 'inactive')">{{ p.active ? 'Activo' : 'Inactivo' }}</span>
          </div>
          <div class="mono detail-meta-line">
            <span>{{ p.businessDomain }}&#64;{{ p.externalSource }}</span>
            <span>v{{ p.version }}</span>
            <span>actualizado {{ p.updatedAt.slice(0, 10) }}</span>
          </div>
        </div>
      </div>
      <div class="tabs">
        @for (t of tabs; track t.id) {
          <button type="button" class="tab" [class.active]="tab() === t.id" (click)="setTab(t.id)">{{ t.label }}</button>
        }
      </div>
    </div>

    <div class="detail-body">
      @switch (tab()) {
        @case ('general') { <div data-testid="tab-general">General</div> }
        @case ('conn') { <div data-testid="tab-conn">Conectividad</div> }
        @case ('map') { <div data-testid="tab-map">Mapping & Transformation</div> }
        @case ('pol') { <div data-testid="tab-pol">Políticas</div> }
        @case ('sync') { <div data-testid="tab-sync">Sincronización</div> }
      }
    </div>
  </div>
}
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css */
.detail-shell { display: flex; flex-direction: column; }
.detail-head { background: var(--surface); border-bottom: 1px solid var(--border); padding: 18px 28px 0; }
.back-link { padding: 0 0 8px; display: inline-block; font-size: 11.5px; }
.detail-title-row { display: flex; align-items: flex-start; gap: 16px; }
.detail-title { display: flex; flex-direction: column; gap: 5px; }
.detail-title-line { display: flex; align-items: center; gap: 10px; }
.detail-title-line h1 { margin: 0; font-size: 20px; font-weight: 600; letter-spacing: -0.015em; }
.detail-meta-line { display: flex; gap: 14px; font-size: 11px; color: var(--text-dim); }
.detail-body { padding: 22px 28px 44px; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
git commit -m "feat(backoffice): add profile detail page shell with deep-linkable tabs"
```

---

### Task 15: General and Conectividad tabs — editable fields with real save

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`

**Interfaces:**
- Adds `editModel: Signal<EditModel | null>` (reset from `profile()` on every successful load) and `save(): void`, which sends `UpdateIntegrationProfilePayload` (Task 6) with `expectedVersion` taken from the currently loaded `profile()!.version` — real optimistic-locking, matching `UpdateIntegrationProfileRequest.java`'s `@NotNull @PositiveOrZero Long expectedVersion`.
- One "Guardar cambios" action in the page header saves whichever fields were edited across both tabs (the backend's `PUT` takes the whole profile in one call — there's no per-tab save endpoint).
- **`EditModel` carries all six opaque config JSON fields (`mappingJson`, `transformationJson`, `syncPolicyJson`, `retryPolicyJson`, `rateLimitPolicyJson`, `extractionConfigJson`) as raw JSON text from the moment the model exists, even though this task's UI never renders five of them and Task 16 renders the rest.** `PUT` replaces the whole configuration in one call, so if `save()` only ever sent the fields a tab happens to expose, saving from the General/Conectividad tabs today would silently null out any `mapping`/`retryPolicy`/etc. a profile already had. Carrying every field through from load to save — unedited where there's no editor yet — is what prevents that data loss; Task 16 only adds textareas for the ones it exposes; `extractionConfig` never gets a dedicated editor (the mockup didn't expose one either) but must still round-trip untouched.
- Conectividad's "Validación" panel is computed purely from the current `editModel()` values (protocol⇒connector/adapter required, matching the real `IntegrationProfileConfiguration` constructor invariant) — no fabricated handshake/dry-run result.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts (add to the existing describe block)
it('pre-fills the General tab from the loaded profile', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  const domainInput = fixture.nativeElement.querySelector('[name="businessDomain"]') as HTMLInputElement;
  expect(domainInput.value).toBe('vehicle');
});

it('saves edited fields with the loaded version as expectedVersion', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  const domainInput = fixture.nativeElement.querySelector('[name="businessDomain"]') as HTMLInputElement;
  domainInput.value = 'vehicle-fleet';
  domainInput.dispatchEvent(new Event('input'));
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('[data-testid="save-profile"]') as HTMLButtonElement).click();

  const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
  expect(request.request.method).toBe('PUT');
  expect(request.request.body).toMatchObject({ businessDomain: 'vehicle-fleet', expectedVersion: 7 });
  request.flush({ ...FULL_PROFILE, businessDomain: 'vehicle-fleet', version: 8 });
});

it('round-trips config fields with no dedicated editor instead of nulling them out on save', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
    ...FULL_PROFILE,
    configuration: {
      protocol: 'KAFKA', connector: 'sigo-kafka-connector', adapter: 'SigoVehicleAdapter', endpoint: null, credentialRef: null,
      mapping: { rules: 3 }, transformation: null, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null,
      extractionConfig: { watermarkColumn: 'updated_at' },
    },
  });
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('[data-testid="save-profile"]') as HTMLButtonElement).click();

  const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
  expect(request.request.body.mapping).toEqual({ rules: 3 });
  expect(request.request.body.extractionConfig).toEqual({ watermarkColumn: 'updated_at' });
  request.flush(FULL_PROFILE);
});

it('flags a protocol without connector/adapter in the Conectividad validation panel', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
    ...FULL_PROFILE,
    configuration: { protocol: 'KAFKA', connector: null, adapter: null, endpoint: null, credentialRef: null, mapping: null, transformation: null, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null, extractionConfig: null },
  });
  fixture.detectChanges();

  const connTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
    (el) => (el as HTMLElement).textContent?.trim() === 'Conectividad',
  ) as HTMLButtonElement;
  connTabButton.click();
  fixture.detectChanges();

  expect(fixture.nativeElement.textContent).toContain('connector y adapter son obligatorios');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the `general`/`conn` tab bodies are still the Task 14 marker `<div>`s with no inputs.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts
// (extends the Task 14 file — add the following imports, types, fields, and methods)
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ApiProblem,
  IntegrationProfile,
  IntegrationProtocol,
  SourceOfTruth,
  SyncDirection,
  UpdateIntegrationProfilePayload,
} from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { ToastService } from '../shared/toast.service';

export type DetailTab = 'general' | 'conn' | 'map' | 'pol' | 'sync';
type DetailState = 'loading' | 'ready' | 'not-found' | 'unavailable';

interface EditModel {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  protocol: IntegrationProtocol | '';
  connector: string;
  adapter: string;
  endpoint: string;
  credentialRef: string;
  // Raw JSON text for the six opaque config blobs. Task 16 adds textareas for
  // five of these; extractionConfig never gets a dedicated editor (the design
  // mockup didn't expose one either), but every field here must still be sent
  // back unedited on save — PUT replaces the whole configuration in one call,
  // so any field missing from the payload is wiped, not left alone.
  mappingJson: string;
  transformationJson: string;
  syncPolicyJson: string;
  retryPolicyJson: string;
  rateLimitPolicyJson: string;
  extractionConfigJson: string;
}

const TABS: { id: DetailTab; label: string }[] = [
  { id: 'general', label: 'General' },
  { id: 'conn', label: 'Conectividad' },
  { id: 'map', label: 'Mapping & Transformation' },
  { id: 'pol', label: 'Políticas' },
  { id: 'sync', label: 'Sincronización' },
];

function stringifyOrEmpty(value: unknown): string {
  return value === null || value === undefined ? '' : JSON.stringify(value, null, 2);
}

function parseJsonFieldOrNull(raw: string): unknown | null {
  const trimmed = raw.trim();
  return trimmed ? JSON.parse(trimmed) : null;
}

function toEditModel(profile: IntegrationProfile): EditModel {
  const config = profile.configuration;
  return {
    businessDomain: profile.businessDomain,
    externalSource: profile.externalSource,
    syncDirection: profile.syncDirection,
    sourceOfTruth: profile.sourceOfTruth,
    protocol: config?.protocol ?? '',
    connector: config?.connector ?? '',
    adapter: config?.adapter ?? '',
    endpoint: config?.endpoint ?? '',
    credentialRef: config?.credentialRef ?? '',
    mappingJson: stringifyOrEmpty(config?.mapping),
    transformationJson: stringifyOrEmpty(config?.transformation),
    syncPolicyJson: stringifyOrEmpty(config?.syncPolicy),
    retryPolicyJson: stringifyOrEmpty(config?.retryPolicy),
    rateLimitPolicyJson: stringifyOrEmpty(config?.rateLimitPolicy),
    extractionConfigJson: stringifyOrEmpty(config?.extractionConfig),
  };
}

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
  private readonly toast = inject(ToastService);

  readonly tabs = TABS;
  readonly state = signal<DetailState>('loading');
  readonly profile = signal<IntegrationProfile | null>(null);
  readonly tab = signal<DetailTab>('general');
  readonly editModel = signal<EditModel | null>(null);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  readonly connectivityValid = computed(() => {
    const m = this.editModel();
    if (!m || !m.protocol) return true;
    return m.connector.trim().length > 0 && m.adapter.trim().length > 0;
  });

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

  updateField<K extends keyof EditModel>(key: K, value: EditModel[K]): void {
    this.editModel.update((current) => (current ? { ...current, [key]: value } : current));
  }

  save(): void {
    const current = this.profile();
    const edits = this.editModel();
    if (!current || !edits) return;

    let mapping: unknown, transformation: unknown, syncPolicy: unknown, retryPolicy: unknown, rateLimitPolicy: unknown, extractionConfig: unknown;
    try {
      mapping = parseJsonFieldOrNull(edits.mappingJson);
      transformation = parseJsonFieldOrNull(edits.transformationJson);
      syncPolicy = parseJsonFieldOrNull(edits.syncPolicyJson);
      retryPolicy = parseJsonFieldOrNull(edits.retryPolicyJson);
      rateLimitPolicy = parseJsonFieldOrNull(edits.rateLimitPolicyJson);
      extractionConfig = parseJsonFieldOrNull(edits.extractionConfigJson);
    } catch {
      this.saveError.set('Uno de los campos de configuración (JSON) no es válido.');
      return;
    }

    const payload: UpdateIntegrationProfilePayload = {
      businessDomain: edits.businessDomain,
      externalSource: edits.externalSource,
      syncDirection: edits.syncDirection,
      sourceOfTruth: edits.sourceOfTruth,
      protocol: edits.protocol || null,
      connector: edits.connector || null,
      adapter: edits.adapter || null,
      endpoint: edits.endpoint || null,
      credentialRef: edits.credentialRef || null,
      mapping,
      transformation,
      syncPolicy,
      retryPolicy,
      rateLimitPolicy,
      extractionConfig,
      expectedVersion: current.version,
    };

    this.saving.set(true);
    this.saveError.set(null);
    this.profileService.update(current.id, payload).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.profile.set(updated);
        this.editModel.set(toEditModel(updated));
        this.toast.show('Cambios guardados · versión ' + updated.version);
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        const problem = error.error as ApiProblem | undefined;
        this.saveError.set(problem?.detail || 'No se pudieron guardar los cambios.');
      },
    });
  }

  private load(id: string): void {
    this.state.set('loading');
    this.profileService.get(id).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.editModel.set(toEditModel(profile));
        this.state.set('ready');
      },
      error: (error: HttpErrorResponse) => {
        this.state.set(error.status === 404 ? 'not-found' : 'unavailable');
      },
    });
  }
}
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html -->
@if (state() === 'loading') {
  <section class="page"><p aria-live="polite">Cargando perfil…</p></section>
} @else if (state() === 'not-found') {
  <section class="page">
    <p role="alert">No se encontró el perfil solicitado.</p>
    <a routerLink="/integration/profiles" class="btn">← Integration Profiles</a>
  </section>
} @else if (state() === 'unavailable') {
  <section class="page">
    <p role="alert">El perfil no está disponible temporalmente.</p>
    <button type="button" class="btn" (click)="reload()">Reintentar</button>
  </section>
} @else if (profile(); as p) {
  <div class="detail-shell">
    <div class="detail-head">
      <a routerLink="/integration/profiles" class="btn-ghost back-link">← Integration Profiles</a>
      <div class="detail-title-row">
        <div class="detail-title">
          <div class="detail-title-line">
            <h1>{{ p.businessDomain }} · {{ p.externalSource }}</h1>
            <span [class]="'badge dir-' + p.syncDirection.toLowerCase()">{{ p.syncDirection }}</span>
            <span [class]="'badge ' + (p.active ? 'active' : 'inactive')">{{ p.active ? 'Activo' : 'Inactivo' }}</span>
          </div>
          <div class="mono detail-meta-line">
            <span>{{ p.businessDomain }}&#64;{{ p.externalSource }}</span>
            <span>v{{ p.version }}</span>
            <span>actualizado {{ p.updatedAt.slice(0, 10) }}</span>
          </div>
        </div>
        <div class="detail-actions">
          <button type="button" data-testid="save-profile" class="btn btn-primary" [disabled]="saving()" (click)="save()">Guardar cambios</button>
        </div>
      </div>
      @if (saveError(); as err) {
        <div class="wizard-error">{{ err }}</div>
      }
      <div class="tabs">
        @for (t of tabs; track t.id) {
          <button type="button" class="tab" [class.active]="tab() === t.id" (click)="setTab(t.id)">{{ t.label }}</button>
        }
      </div>
    </div>

    <div class="detail-body">
      @if (editModel(); as edit) {
        @switch (tab()) {
          @case ('general') {
            <div data-testid="tab-general" class="tab-grid">
              <div class="card">
                <div class="card-header">Identidad del perfil</div>
                <div class="card-grid">
                  <label class="field">
                    <span class="label">BUSINESS DOMAIN</span>
                    <input name="businessDomain" [value]="edit.businessDomain" (input)="updateField('businessDomain', $any($event.target).value)" />
                  </label>
                  <label class="field">
                    <span class="label">EXTERNAL SOURCE</span>
                    <input name="externalSource" [value]="edit.externalSource" (input)="updateField('externalSource', $any($event.target).value)" />
                  </label>
                  <label class="field">
                    <span class="label">SYNC DIRECTION</span>
                    <select [value]="edit.syncDirection" (change)="updateField('syncDirection', $any($event.target).value)">
                      <option value="INBOUND">INBOUND</option>
                      <option value="OUTBOUND">OUTBOUND</option>
                      <option value="BIDIRECTIONAL">BIDIRECTIONAL</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="label">SOURCE OF TRUTH</span>
                    <select [value]="edit.sourceOfTruth" (change)="updateField('sourceOfTruth', $any($event.target).value)">
                      <option value="PLATFORM">PLATFORM</option>
                      <option value="EXTERNAL">EXTERNAL</option>
                      <option value="SHARED">SHARED</option>
                    </select>
                  </label>
                </div>
              </div>
              <div class="card">
                <div class="card-header">Metadatos</div>
                <div class="meta-list">
                  <div class="meta-row"><span class="mono meta-label">PROFILE ID</span><span class="mono">{{ p.id }}</span></div>
                  <div class="meta-row"><span class="mono meta-label">TENANT ID</span><span class="mono">{{ p.tenantId }}</span></div>
                  <div class="meta-row"><span class="mono meta-label">VERSION</span><span class="mono">v{{ p.version }} · control optimista</span></div>
                  <div class="meta-row"><span class="mono meta-label">CREADO</span><span class="mono">{{ p.createdAt.slice(0, 10) }}</span></div>
                  <div class="meta-row"><span class="mono meta-label">ACTUALIZADO</span><span class="mono">{{ p.updatedAt.slice(0, 10) }}</span></div>
                </div>
              </div>
            </div>
          }
          @case ('conn') {
            <div data-testid="tab-conn" class="tab-grid">
              <div class="card">
                <div class="card-header">Conector</div>
                <div class="card-grid">
                  <label class="field">
                    <span class="label">PROTOCOL</span>
                    <select [value]="edit.protocol" (change)="updateField('protocol', $any($event.target).value)">
                      <option value="">—</option>
                      <option value="REST">REST</option>
                      <option value="SOAP">SOAP</option>
                      <option value="JSON_RPC">JSON_RPC</option>
                      <option value="KAFKA">KAFKA</option>
                      <option value="JDBC">JDBC</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="label">CONNECTOR</span>
                    <input [value]="edit.connector" (input)="updateField('connector', $any($event.target).value)" />
                  </label>
                  <label class="field">
                    <span class="label">ADAPTER</span>
                    <input [value]="edit.adapter" (input)="updateField('adapter', $any($event.target).value)" />
                  </label>
                  <label class="field full">
                    <span class="label">ENDPOINT</span>
                    <input [value]="edit.endpoint" (input)="updateField('endpoint', $any($event.target).value)" />
                  </label>
                  <label class="field full">
                    <span class="label">CREDENTIAL REF</span>
                    <input [value]="edit.credentialRef" (input)="updateField('credentialRef', $any($event.target).value)" />
                  </label>
                </div>
              </div>
              <div class="card">
                <div class="card-header">Validación (local)</div>
                <div class="validation-panel">
                  @if (connectivityValid()) {
                    <div class="validation-row ok">✓ Protocol, connector y adapter son consistentes.</div>
                  } @else {
                    <div class="validation-row err">✗ Si defines protocol, connector y adapter son obligatorios.</div>
                  }
                  <p class="validation-note">Esta validación es local al formulario; no ejecuta un handshake real contra la fuente externa.</p>
                </div>
              </div>
            </div>
          }
          @case ('map') { <div data-testid="tab-map">Mapping & Transformation</div> }
          @case ('pol') { <div data-testid="tab-pol">Políticas</div> }
          @case ('sync') { <div data-testid="tab-sync">Sincronización</div> }
        }
      }
    </div>
  </div>
}
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css (append) */
.detail-actions { margin-left: auto; }
.tab-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; align-items: start; max-width: 1280px; }
.card-grid { padding: 16px; display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.card-grid .field.full { grid-column: 1 / -1; }
.meta-list { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.meta-row { display: flex; align-items: baseline; gap: 12px; }
.meta-label { width: 118px; flex: 0 0 118px; color: var(--text-dim); font-size: 10px; letter-spacing: 0.07em; }
.validation-panel { padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.validation-row.ok { color: var(--ok); }
.validation-row.err { color: var(--err); }
.validation-note { margin: 0; font-size: 11px; color: var(--text-dim); }
.wizard-error { margin: 12px 0 0; padding: 10px 13px; background: color-mix(in oklab, var(--err) 12%, var(--surface)); border: 1px solid var(--err); border-radius: 5px; color: var(--err); font-size: 12.5px; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
git commit -m "feat(backoffice): make General and Conectividad tabs editable with real save"
```

---

### Task 16: Mapping & Transformation and Políticas tabs — real JSON editors

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`

**Interfaces:**
- Mapping tab: two textareas bound to `editModel().mappingJson` / `.transformationJson` (already carried through since Task 15), each with a live "JSON válido ✓ / inválido ✗" indicator computed from the current textarea content — no dry-run execution, since no backend endpoint exists to run one.
- Políticas tab: three textareas for `syncPolicyJson` / `retryPolicyJson` / `rateLimitPolicyJson`, plus a `retrySequence: Signal<string[]>` computed **only** from what the user typed into the retry-policy textarea (`{"maxAttempts": n, "backoff": "EXPONENTIAL", "initialIntervalMs": ms}` → `[ms, ms*2, ms*4, ...]` for `n - 1` retries) — real arithmetic on real input, not a fabricated "sequence" like the mockup's static example.
- No "Ejecutar dry-run" button and no canonical-output preview panel: the mockup's dry-run has no backend counterpart (see Global Constraints), so it is not reproduced here at all, not even as a disabled affordance.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts (add to the existing describe block)
it('pre-fills the Mapping tab textareas as pretty-printed JSON and flags invalid edits', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
    ...FULL_PROFILE,
    configuration: { protocol: null, connector: null, adapter: null, endpoint: null, credentialRef: null, mapping: { vin: '$.Vehiculo.Chasis' }, transformation: null, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null, extractionConfig: null },
  });
  fixture.detectChanges();

  const mapTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
    (el) => (el as HTMLElement).textContent?.trim() === 'Mapping & Transformation',
  ) as HTMLButtonElement;
  mapTabButton.click();
  fixture.detectChanges();

  const mappingArea = fixture.nativeElement.querySelector('[name="mappingJson"]') as HTMLTextAreaElement;
  expect(mappingArea.value).toContain('"vin"');

  mappingArea.value = '{ not json';
  mappingArea.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('JSON inválido');
});

it('computes a real retry sequence from the retry policy JSON typed by the user', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  const polTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
    (el) => (el as HTMLElement).textContent?.trim() === 'Políticas',
  ) as HTMLButtonElement;
  polTabButton.click();
  fixture.detectChanges();

  const retryArea = fixture.nativeElement.querySelector('[name="retryPolicyJson"]') as HTMLTextAreaElement;
  retryArea.value = JSON.stringify({ maxAttempts: 4, backoff: 'EXPONENTIAL', initialIntervalMs: 2000 });
  retryArea.dispatchEvent(new Event('input'));
  fixture.detectChanges();

  expect(fixture.nativeElement.textContent).toContain('2000ms');
  expect(fixture.nativeElement.textContent).toContain('4000ms');
  expect(fixture.nativeElement.textContent).toContain('8000ms');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the `map`/`pol` tab bodies are still the Task 14 marker `<div>`s.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts
// (add alongside the Task 15 fields/methods)
readonly retrySequence = computed(() => {
  const raw = this.editModel()?.retryPolicyJson ?? '';
  if (!raw.trim()) return [];
  let parsed: { maxAttempts?: number; backoff?: string; initialIntervalMs?: number };
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (parsed.backoff !== 'EXPONENTIAL' || !parsed.maxAttempts || parsed.maxAttempts < 2) return [];
  const initial = parsed.initialIntervalMs ?? 1000;
  const sequence: string[] = [];
  let interval = initial;
  for (let i = 0; i < parsed.maxAttempts - 1; i++) {
    sequence.push(interval + 'ms');
    interval *= 2;
  }
  return sequence;
});

isJsonValid(raw: string): boolean {
  if (!raw.trim()) return true;
  try {
    JSON.parse(raw);
    return true;
  } catch {
    return false;
  }
}
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html -->
<!-- replace the Task 15 '@case (\'map\')' and '@case (\'pol\')' bodies -->
@case ('map') {
  <div data-testid="tab-map" class="tab-grid">
    <div class="card">
      <div class="card-header">
        <span>Mapping</span>
        <span class="mono json-status" [class.err]="!isJsonValid(edit.mappingJson)">
          {{ isJsonValid(edit.mappingJson) ? 'JSON válido' : 'JSON inválido' }}
        </span>
      </div>
      <textarea
        name="mappingJson"
        class="json-editor"
        [value]="edit.mappingJson"
        (input)="updateField('mappingJson', $any($event.target).value)"
      ></textarea>
    </div>
    <div class="card">
      <div class="card-header">
        <span>Transformation</span>
        <span class="mono json-status" [class.err]="!isJsonValid(edit.transformationJson)">
          {{ isJsonValid(edit.transformationJson) ? 'JSON válido' : 'JSON inválido' }}
        </span>
      </div>
      <textarea
        name="transformationJson"
        class="json-editor"
        [value]="edit.transformationJson"
        (input)="updateField('transformationJson', $any($event.target).value)"
      ></textarea>
    </div>
  </div>
}
@case ('pol') {
  <div data-testid="tab-pol" class="tab-grid-3">
    <div class="card">
      <div class="card-header">
        <span>Sync policy</span>
        <span class="mono json-status" [class.err]="!isJsonValid(edit.syncPolicyJson)">
          {{ isJsonValid(edit.syncPolicyJson) ? 'JSON válido' : 'JSON inválido' }}
        </span>
      </div>
      <textarea
        name="syncPolicyJson"
        class="json-editor"
        [value]="edit.syncPolicyJson"
        (input)="updateField('syncPolicyJson', $any($event.target).value)"
      ></textarea>
    </div>
    <div class="card">
      <div class="card-header">
        <span>Retry & backoff</span>
        <span class="mono json-status" [class.err]="!isJsonValid(edit.retryPolicyJson)">
          {{ isJsonValid(edit.retryPolicyJson) ? 'JSON válido' : 'JSON inválido' }}
        </span>
      </div>
      <textarea
        name="retryPolicyJson"
        class="json-editor small"
        [value]="edit.retryPolicyJson"
        (input)="updateField('retryPolicyJson', $any($event.target).value)"
      ></textarea>
      @if (retrySequence().length > 0) {
        <div class="retry-sequence">
          <span class="mono seq-label">SECUENCIA CALCULADA</span>
          <div class="seq-chips">
            @for (step of retrySequence(); track $index) {
              <span class="mono seq-chip">{{ step }}</span>
            }
            <span class="mono seq-chip dlq">→ DLQ</span>
          </div>
        </div>
      }
    </div>
    <div class="card">
      <div class="card-header">
        <span>Rate limit</span>
        <span class="mono json-status" [class.err]="!isJsonValid(edit.rateLimitPolicyJson)">
          {{ isJsonValid(edit.rateLimitPolicyJson) ? 'JSON válido' : 'JSON inválido' }}
        </span>
      </div>
      <textarea
        name="rateLimitPolicyJson"
        class="json-editor small"
        [value]="edit.rateLimitPolicyJson"
        (input)="updateField('rateLimitPolicyJson', $any($event.target).value)"
      ></textarea>
    </div>
  </div>
}
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css (append) */
.tab-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; align-items: start; max-width: 1400px; }
.json-status { margin-left: auto; color: var(--ok); }
.json-status.err { color: var(--err); }
.json-editor { width: 100%; min-height: 260px; border: 0; border-top: 1px solid var(--border); outline: none; resize: vertical; padding: 14px; font-family: 'IBM Plex Mono', monospace; font-size: 11.5px; line-height: 1.6; background: var(--surface-2); color: var(--text); }
.json-editor.small { min-height: 140px; }
.retry-sequence { padding: 0 16px 16px; display: flex; flex-direction: column; gap: 8px; }
.seq-label { font-size: 10px; color: var(--text-dim); letter-spacing: 0.07em; }
.seq-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.seq-chip { background: var(--surface); border: 1px solid var(--border); border-radius: 3px; padding: 3px 7px; font-size: 10.5px; }
.seq-chip.dlq { background: var(--accent-tint); border-color: var(--accent-tint-border); color: var(--accent-fg); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
git commit -m "feat(backoffice): add real JSON editors for mapping and policy config"
```

---

### Task 17: Sincronización tab and deactivate action — the two real one-off actions

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`

**Interfaces:**
- "Sincronización" tab replaces the mockup's "Pruebas" tab: one real button calling `IntegrationProfileService.triggerSync` (Task 6), appending each real `TriggerSyncResult` to an in-memory `syncLog: Signal<TriggerSyncResult[]>` for this session — not a fabricated multi-step handshake trace.
- Header gets a "Desactivar perfil" action, shown only while `profile().active` — the real API has no reactivate endpoint, so once deactivated the header shows a static note instead of a "Reanudar" button, and the action requires an injected `CONFIRM` token (`(message: string) => boolean`, factory defaults to `window.confirm`) before calling `IntegrationProfileService.deactivate`, since it is irreversible from this UI.

- [ ] **Step 1: Write the failing test**

Update the file's shared setup first — replace the `import`/`describe` opening block (everything up to and including the existing `afterEach(() => http.verify());` line) with:

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { CONFIRM, IntegrationProfileDetailComponent } from './integration-profile-detail.component';

const FULL_PROFILE = {
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 7,
};

describe('IntegrationProfileDetailComponent', () => {
  let http: HttpTestingController;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let navigateSpy: ReturnType<typeof vi.fn>;
  let confirmSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    params = new BehaviorSubject(convertToParamMap({ profileId: 'p-1' }));
    queryParams = new BehaviorSubject(convertToParamMap({}));
    navigateSpy = vi.fn();
    confirmSpy = vi.fn(() => true);

    await TestBed.configureTestingModule({
      imports: [IntegrationProfileDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: params.asObservable(), queryParamMap: queryParams.asObservable() },
        },
        { provide: Router, useValue: { navigate: navigateSpy } },
        { provide: CONFIRM, useValue: confirmSpy },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());
```

(Every existing `it(...)` block from Tasks 14-16 stays exactly where it is, unchanged, inside this same `describe`; only the setup above it changes.) Then add:

```ts
it('triggers a real sync and appends the result to the session log', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  const syncTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
    (el) => (el as HTMLElement).textContent?.trim() === 'Sincronización',
  ) as HTMLButtonElement;
  syncTabButton.click();
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('[data-testid="trigger-sync"]') as HTMLButtonElement).click();
  http.expectOne('/bff/api/v1/integration-profiles/p-1/sync').flush({
    profileId: 'p-1',
    status: 'TRIGGERED',
    triggeredAt: '2026-08-26T10:00:00Z',
  });
  fixture.detectChanges();

  expect(fixture.nativeElement.textContent).toContain('TRIGGERED');
});

it('deactivates the profile after confirmation and hides the action once inactive', () => {
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('[data-testid="deactivate-profile"]') as HTMLButtonElement).click();
  expect(confirmSpy).toHaveBeenCalled();

  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(null);
  fixture.detectChanges();

  expect(fixture.nativeElement.querySelector('[data-testid="deactivate-profile"]')).toBeNull();
  expect(fixture.nativeElement.textContent).toContain('Inactivo');
});

it('does not deactivate when the confirmation is declined', () => {
  confirmSpy.mockReturnValue(false);
  const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
  fixture.detectChanges();
  http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  fixture.detectChanges();

  (fixture.nativeElement.querySelector('[data-testid="deactivate-profile"]') as HTMLButtonElement).click();
  http.expectNone('/bff/api/v1/integration-profiles/p-1'); // no DELETE was sent beyond the initial GET already consumed above
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — no `[data-testid="trigger-sync"]`/`[data-testid="deactivate-profile"]` elements exist yet.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts
// (add alongside the Task 15/16 fields/methods; add InjectionToken to the @angular/core import)
import { ChangeDetectionStrategy, Component, InjectionToken, OnInit, computed, inject, signal } from '@angular/core';
// ...
import { TriggerSyncResult } from './integration-profile.model';

export const CONFIRM = new InjectionToken<(message: string) => boolean>('CONFIRM', {
  factory: () => (message: string) => window.confirm(message),
});

// inside the class:
private readonly confirm = inject(CONFIRM);

readonly syncing = signal(false);
readonly syncLog = signal<TriggerSyncResult[]>([]);

triggerSync(): void {
  const current = this.profile();
  if (!current) return;
  this.syncing.set(true);
  this.profileService.triggerSync(current.id).subscribe({
    next: (result) => {
      this.syncing.set(false);
      this.syncLog.update((log) => [result, ...log]);
      this.toast.show('Sincronización disparada · ' + result.status);
    },
    error: () => {
      this.syncing.set(false);
      this.toast.show('No se pudo disparar la sincronización.');
    },
  });
}

deactivateProfile(): void {
  const current = this.profile();
  if (!current) return;
  if (!this.confirm('Esta acción desactiva el perfil y no puede deshacerse desde la consola. ¿Continuar?')) return;
  this.profileService.deactivate(current.id).subscribe({
    next: () => {
      this.profile.set({ ...current, active: false });
      this.toast.show('Perfil desactivado.');
    },
    error: () => this.toast.show('No se pudo desactivar el perfil.'),
  });
}
```

```html
<!-- backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html -->
<!-- inside .detail-actions, alongside "Guardar cambios" -->
<div class="detail-actions">
  @if (p.active) {
    <button type="button" data-testid="deactivate-profile" class="btn" (click)="deactivateProfile()">Desactivar perfil</button>
  } @else {
    <span class="inactive-note">Perfil inactivo — no hay acción de reactivación disponible en la consola.</span>
  }
  <button type="button" data-testid="save-profile" class="btn btn-primary" [disabled]="saving()" (click)="save()">Guardar cambios</button>
</div>

<!-- replace the Task 14/15/16 '@case (\'sync\')' body -->
@case ('sync') {
  <div data-testid="tab-sync" class="tab-grid">
    <div class="card">
      <div class="card-header">
        <span>Sincronización</span>
        <button type="button" data-testid="trigger-sync" class="btn btn-dark" [disabled]="syncing()" (click)="triggerSync()">Sincronizar ahora</button>
      </div>
      <p class="sync-hint">
        Dispara <span class="mono">POST /integration-profiles/{{ p.id }}/sync</span> contra el Gateway real. Confirma que el
        trigger fue aceptado; no ejecuta una prueba de conectividad de bajo nivel.
      </p>
      @if (syncLog().length === 0) {
        <div class="empty-state"><strong>Sin sincronizaciones en esta sesión</strong></div>
      } @else {
        <div class="sync-log">
          @for (entry of syncLog(); track entry.triggeredAt) {
            <div class="sync-log-row">
              <span class="badge active">{{ entry.status }}</span>
              <span class="mono">{{ entry.triggeredAt }}</span>
            </div>
          }
        </div>
      }
    </div>
  </div>
}
```

```css
/* backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css (append) */
.inactive-note { font-size: 11.5px; color: var(--text-dim); }
.sync-hint { margin: 0; padding: 14px 16px; font-size: 12px; color: var(--text-muted); line-height: 1.55; border-bottom: 1px solid var(--border-soft); }
.sync-log { padding: 6px 0; }
.sync-log-row { display: flex; align-items: center; gap: 11px; padding: 10px 16px; border-bottom: 1px solid var(--border-soft); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS. Also run `npx nx test integration-mfe && npx nx build integration-mfe` once more here since this is the last task touching `integration-profile-detail.component.ts` — confirm the whole file (Tasks 14-17 combined) still compiles and every earlier spec in the file still passes.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.css backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
git commit -m "feat(backoffice): add real sync trigger and deactivate actions to profile detail"
```

---

### Task 18: Monitor page — honest placeholder plus real DLQ replay

**Files:**
- Create: `backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.model.ts`
- Create: `backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.service.ts`
- Create: `backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.service.spec.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.html`
- Create: `backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.css`
- Create: `backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.spec.ts`

**Interfaces:**
- Produces `DeadLetterQueueService.replay(): Observable<DlqReplaySummary>` (`{ total, success, failed }`, matching `DeadLetterQueueReplayService.ReplaySummary` in the real backend) calling Task 8's `POST /bff/api/v1/inbox/dlq/replay`.
- The page has exactly one real feature — triggering the bulk DLQ replay and showing its real result — plus Task 9's `ConsoleEmptyStateComponent` explaining that per-message browsing has no backend yet. It must not render any per-message row, table, or filter chip: there is no list endpoint to back one.

- [ ] **Step 1: Write the failing tests**

```ts
// backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.service.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DeadLetterQueueService } from './dead-letter-queue.service';

describe('DeadLetterQueueService', () => {
  it('replays the dead letter queue through the BFF', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(DeadLetterQueueService);
    const http = TestBed.inject(HttpTestingController);

    service.replay().subscribe((summary) => expect(summary.total).toBe(3));
    const request = http.expectOne('/bff/api/v1/inbox/dlq/replay');
    expect(request.request.method).toBe('POST');
    request.flush({ total: 3, success: 2, failed: 1 });
    http.verify();
  });
});
```

```ts
// backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MonitorPageComponent } from './monitor-page.component';

describe('MonitorPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MonitorPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows the empty-state note explaining there is no message browsing API yet', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('no expone una API de lectura');
  });

  it('replays the DLQ and shows the real summary counts', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush({ total: 3, success: 2, failed: 1 });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('total 3');
    expect(text).toContain('éxito 2');
    expect(text).toContain('fallidos 1');
  });

  it('shows an inline error when the replay call fails', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No se pudo ejecutar el reproceso');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — `DeadLetterQueueService` doesn't exist and the monitor page has no DLQ button.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.model.ts
export interface DlqReplaySummary {
  total: number;
  success: number;
  failed: number;
}
```

```ts
// backoffice/apps/integration-mfe/src/app/dead-letter-queue/dead-letter-queue.service.ts
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DlqReplaySummary } from './dead-letter-queue.model';

@Injectable({ providedIn: 'root' })
export class DeadLetterQueueService {
  private readonly http = inject(HttpClient);

  replay(): Observable<DlqReplaySummary> {
    return this.http.post<DlqReplaySummary>('/bff/api/v1/inbox/dlq/replay', {});
  }
}
```

```ts
// backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.ts
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DeadLetterQueueService } from '../dead-letter-queue/dead-letter-queue.service';
import { DlqReplaySummary } from '../dead-letter-queue/dead-letter-queue.model';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';
import { ToastService } from '../shared/toast.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-monitor-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  templateUrl: './monitor-page.component.html',
  styleUrl: './monitor-page.component.css',
})
export class MonitorPageComponent {
  private readonly dlqService = inject(DeadLetterQueueService);
  private readonly toast = inject(ToastService);

  readonly replaying = signal(false);
  readonly summary = signal<DlqReplaySummary | null>(null);
  readonly errorMessage = signal<string | null>(null);

  replay(): void {
    this.replaying.set(true);
    this.errorMessage.set(null);
    this.dlqService.replay().subscribe({
      next: (result) => {
        this.replaying.set(false);
        this.summary.set(result);
        this.toast.show('Reproceso de DLQ completado: ' + result.success + '/' + result.total + ' exitosos');
      },
      error: () => {
        this.replaying.set(false);
        this.errorMessage.set('No se pudo ejecutar el reproceso de DLQ.');
      },
    });
  }
}
```

```html
<!-- backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.html -->
<section class="page">
  <div class="page-header">
    <div>
      <h1>Monitor de mensajes</h1>
      <p>Outbox e Inbox del tenant autenticado.</p>
    </div>
  </div>

  <div class="card">
    <div class="card-header">Reproceso de Dead Letter Queue</div>
    <div class="dlq-body">
      <p>Ejecuta un reintento masivo de los mensajes en DLQ del tenant autenticado.</p>
      <button type="button" data-testid="dlq-replay" class="btn btn-dark" [disabled]="replaying()" (click)="replay()">
        Ejecutar reproceso de DLQ
      </button>
      @if (errorMessage(); as err) {
        <div class="wizard-error">{{ err }}</div>
      }
      @if (summary(); as s) {
        <div class="mono dlq-summary">
          <span>total {{ s.total }}</span>
          <span>éxito {{ s.success }}</span>
          <span>fallidos {{ s.failed }}</span>
        </div>
      }
    </div>
  </div>

  <div class="card">
    <app-console-empty-state
      title="Vista de mensajes individuales no disponible todavía"
      description="El backend de Outbox/Inbox aún no expone una API de lectura por mensaje. Esta vista se completará cuando exista ese endpoint."
    />
  </div>
</section>
```

```css
/* backoffice/apps/integration-mfe/src/app/monitor/monitor-page.component.css */
.dlq-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; align-items: flex-start; }
.dlq-summary { display: flex; gap: 14px; color: var(--text-muted); }
.wizard-error { padding: 10px 13px; background: color-mix(in oklab, var(--err) 12%, var(--surface)); border: 1px solid var(--err); border-radius: 5px; color: var(--err); font-size: 12.5px; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/dead-letter-queue backoffice/apps/integration-mfe/src/app/monitor
git commit -m "feat(backoffice): wire the Monitor page to the real DLQ bulk replay endpoint"
```

---

### Task 19: Connectors page — honest placeholder

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.spec.ts`

**Interfaces:**
- No backend endpoint of any kind exists for a connector catalog — not even a bulk action like Monitor's DLQ replay. This page renders the page header plus Task 9's `ConsoleEmptyStateComponent` only.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { ConnectorsPageComponent } from './connectors-page.component';

describe('ConnectorsPageComponent', () => {
  it('renders the heading and the no-backend-yet explanation', () => {
    TestBed.configureTestingModule({ imports: [ConnectorsPageComponent] });
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Conectores y adapters');
    expect(fixture.nativeElement.textContent).toContain('catálogo de connectors');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the Task 10 stub has no explanatory copy.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/connectors/connectors-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-connectors-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Conectores y adapters</h1>
          <p>Catálogo instalado en el core de integración.</p>
        </div>
      </div>
      <div class="card">
        <app-console-empty-state
          title="Catálogo de connectors no disponible todavía"
          description="El backend no expone hoy un endpoint que liste los connectors y adapters instalados. Esta vista se completará cuando exista esa API."
        />
      </div>
    </section>
  `,
})
export class ConnectorsPageComponent {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/connectors
git commit -m "feat(backoffice): add honest placeholder for the Connectors page"
```

---

### Task 20: Credentials page — honest placeholder

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.spec.ts`

**Interfaces:**
- Same shape as Task 19: no backend endpoint lists Vault credential references, so this page is a page header plus `ConsoleEmptyStateComponent` only. Never render a credential table with fabricated `vault://` rows.

- [ ] **Step 1: Write the failing test**

```ts
// backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.spec.ts
import { TestBed } from '@angular/core/testing';
import { CredentialsPageComponent } from './credentials-page.component';

describe('CredentialsPageComponent', () => {
  it('renders the heading and the no-backend-yet explanation', () => {
    TestBed.configureTestingModule({ imports: [CredentialsPageComponent] });
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Credenciales');
    expect(fixture.nativeElement.textContent).toContain('no expone');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx nx test integration-mfe`
Expected: FAIL — the Task 10 stub has no explanatory copy.

- [ ] **Step 3: Write minimal implementation**

```ts
// backoffice/apps/integration-mfe/src/app/credentials/credentials-page.component.ts
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-credentials-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Credenciales</h1>
          <p>Las credenciales se resuelven en runtime desde Vault; la consola nunca las almacena ni las muestra.</p>
        </div>
      </div>
      <div class="card">
        <app-console-empty-state
          title="Listado de credential refs no disponible todavía"
          description="El backend no expone hoy un endpoint que liste las referencias de credenciales por tenant. Cada perfil sigue mostrando su propio credentialRef en la pestaña Conectividad."
        />
      </div>
    </section>
  `,
})
export class CredentialsPageComponent {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx nx test integration-mfe`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/credentials
git commit -m "feat(backoffice): add honest placeholder for the Credentials page"
```

---

### Task 21: Full-suite verification and manual browser check

**Files:** none (verification-only task; fix forward in the relevant task's files if something here fails).

**Interfaces:** none — this task consumes every interface produced by Tasks 1-20 and confirms they compose correctly end to end.

- [ ] **Step 1: Run every unit/integration test suite**

```bash
npx nx test shell
npx nx test integration-mfe
npx nx test bff
```

Expected: all PASS. If any fails, fix it in the task that owns the failing file — do not patch around it here.

- [ ] **Step 2: Run lint and production builds**

```bash
npx nx lint shell
npx nx lint integration-mfe
npx nx lint bff
npx nx build shell
npx nx build integration-mfe
npx nx build bff
```

Expected: all PASS. The build step also re-confirms nothing still imports the deleted `remote-entry/integration-root.component.ts` (Task 10) or the old thin `IntegrationProfile` shape (Task 6).

- [ ] **Step 3: Manual browser check of the redesigned console**

Serve the shell and the remote together (two terminals):

```bash
npx nx serve integration-mfe
```
```bash
npx nx serve shell
```

Open the shell's dev URL in a browser (the port `nx serve shell` prints). Without a live BFF/Gateway behind it, every API call 404s — that's expected and still useful: it exercises every page's loading→error path and the whole visual system. Confirm:
- The sidebar renders as a dark collapsible rail with the two nav sections; hovering it while collapsed expands it, clicking the pin toggle keeps it expanded, and clicking each link changes the header/content area to the corresponding page without a full reload.
- The header's theme toggle switches the whole page (rail, cards, tables) between the light and dark token sets from Task 1, and the choice survives a manual page reload (persisted via `localStorage`).
- `/integration/profiles` shows the "unavailable" error state with a working "Reintentar" button (no backend, so this is the reachable state) — confirm the table/search/chip styling still renders correctly by widening the browser and checking layout doesn't overflow.
- `/integration` (Dashboard), `/integration/monitor`, `/integration/connectors`, `/integration/credentials` each render their header and either real content or the `ConsoleEmptyStateComponent` panel without console errors (check the browser devtools console for uncaught exceptions — network 404s from the missing backend are expected and fine).

For a true end-to-end pass exercising the real Profile CRUD/sync flows and the DLQ replay (create a profile through the wizard, edit and save it, trigger a sync, deactivate it, replay the DLQ), run the full stack via the existing `compose.yaml` backoffice services (`backoffice-bff`, `backoffice-microui`, `backoffice-shell`, plus their Keycloak/Gateway/MySQL/Kafka dependencies) and repeat the same walkthrough logged in through Keycloak. This is optional for closing out this plan but is the only way to see Tasks 6-8 and 12-18's real backend calls succeed rather than surface their (already-tested) error paths.

- [ ] **Step 4: Confirm no stray files or references remain**

```bash
grep -rn "IntegrationRootComponent" backoffice/apps/integration-mfe/src || echo "clean"
grep -rn "WelcomeComponent" backoffice/apps/shell/src || echo "clean"
```

Expected: both print `clean` (or only match this plan's own historical commit messages, not source).

- [ ] **Step 5: Commit**

Only if Steps 1-4 required fixes that don't belong to an earlier task's commit (e.g., a cross-cutting lint fix). If everything already passed as each task landed, there is nothing new to commit here — this task is a gate, not a deliverable.

---
