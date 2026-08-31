import { firstValueFrom, Observable, of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { authGuard } from './auth.guard';
import { SessionService } from './session/session.service';

describe('authGuard', () => {
  it('allows navigation when the BFF session is authenticated', async () => {
    const sessionService = {
      check: vi.fn(() => of({ authenticated: true })),
      login: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: SessionService, useValue: sessionService }] });

    const result = await firstValueFrom(
      TestBed.runInInjectionContext(() => authGuard({} as never, {} as never)) as Observable<boolean>,
    );

    expect(result).toBe(true);
    expect(sessionService.login).not.toHaveBeenCalled();
  });

  it('redirects anonymous navigation to the BFF login endpoint', async () => {
    const sessionService = {
      check: vi.fn(() => of({ authenticated: false })),
      login: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: SessionService, useValue: sessionService }] });

    const result = await firstValueFrom(
      TestBed.runInInjectionContext(() => authGuard({} as never, {} as never)) as Observable<boolean>,
    );

    expect(result).toBe(false);
    expect(sessionService.login).toHaveBeenCalledOnce();
  });
});
