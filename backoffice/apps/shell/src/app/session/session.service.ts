import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';

export interface Session {
  authenticated: boolean;
  tenantId?: string;
  expiresAt?: number;
}

interface BrowserWindow {
  location: Pick<Location, 'assign'>;
}

export const WINDOW = new InjectionToken<BrowserWindow>('WINDOW', {
  factory: () => window,
  providedIn: 'root',
});

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly window = inject(WINDOW);

  readonly session = signal<Session>({ authenticated: false });

  refresh(): void {
    this.http
      .get<Session>('/auth/session', { withCredentials: true })
      .subscribe({
        next: (session: Session) => this.session.set(session),
        error: () => this.session.set({ authenticated: false }),
      });
  }

  login(): void {
    this.window.location.assign('/auth/login');
  }

  logout(): void {
    this.window.location.assign('/auth/logout');
  }
}
