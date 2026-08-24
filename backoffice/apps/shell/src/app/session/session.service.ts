import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export interface Session {
  authenticated: boolean;
  tenantId?: string;
  expiresAt?: number;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);

  readonly session = signal<Session>({ authenticated: false });

  refresh(): void {
    this.http
      .get<Session>('/auth/session', { withCredentials: true })
      .subscribe({
        next: (session) => this.session.set(session),
        error: () => this.session.set({ authenticated: false }),
      });
  }

  login(): void {
    window.location.assign('/auth/login');
  }

  logout(): void {
    window.location.assign('/auth/logout');
  }
}
