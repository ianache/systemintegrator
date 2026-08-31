import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { catchError, Observable, of, tap } from 'rxjs';

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
    this.check().subscribe();
  }

  check(): Observable<Session> {
    return this.http
      .get<Session>('/auth/session', { withCredentials: true })
      .pipe(
        tap((session) => this.session.set(session)),
        catchError(() => {
          const anonymousSession = { authenticated: false };
          this.session.set(anonymousSession);
          return of(anonymousSession);
        }),
      );
  }

  login(): void {
    this.window.location.assign('/auth/login');
  }

  logout(): void {
    this.window.location.assign('/auth/logout');
  }
}
