import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SessionService, WINDOW } from './session.service';

describe('SessionService', () => {
  let service: SessionService;
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WINDOW, useValue: { location: { assign } } },
      ],
    });

    service = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps an authenticated session response from the BFF', () => {
    service.refresh();

    const request = http.expectOne('/auth/session');
    expect(request.request.withCredentials).toBe(true);
    request.flush({
      authenticated: true,
      tenantId: 'tenant-a',
      expiresAt: 1893456000,
    });

    expect(service.session()).toEqual({
      authenticated: true,
      tenantId: 'tenant-a',
      expiresAt: 1893456000,
    });
  });

  it('maps an anonymous session response from the BFF', () => {
    service.refresh();

    http.expectOne('/auth/session').flush({ authenticated: false });

    expect(service.session()).toEqual({ authenticated: false });
  });

  it('keeps the anonymous state when the session request fails', () => {
    service.refresh();

    http.expectOne('/auth/session').flush('Unavailable', {
      status: 503,
      statusText: 'Service Unavailable',
    });

    expect(service.session()).toEqual({ authenticated: false });
  });

  it('navigates to the BFF login and logout endpoints', () => {
    service.login();
    expect(assign).toHaveBeenLastCalledWith('/auth/login');

    service.logout();
    expect(assign).toHaveBeenLastCalledWith('/auth/logout');
  });
});
