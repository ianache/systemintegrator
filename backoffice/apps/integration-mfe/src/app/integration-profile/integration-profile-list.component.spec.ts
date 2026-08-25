import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileListComponent, WINDOW } from './integration-profile-list.component';

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
        { provide: WINDOW, useValue: { location: { assign } } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows a loading status while profiles are being requested', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Loading integration profiles');
    http.expectOne('/bff/api/v1/integration-profiles');
  });

  it('renders a read-only table for loaded profiles', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles').flush([
      {
        id: 'p-1',
        businessDomain: 'orders',
        externalSource: 'erp',
        syncDirection: 'INBOUND',
        active: true,
        version: 2,
      },
    ]);
    fixture.detectChanges();

    const table = fixture.nativeElement.querySelector('table') as HTMLTableElement;
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('orders');
    expect(table.textContent).toContain('Active');
    expect(fixture.nativeElement.querySelectorAll('button, a[href]').length).toBe(0);
  });

  it('shows an empty state when no profiles are returned', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No integration profiles are available');
  });

  it.each([401, 403])('shows a session state and redirects to login for %i responses', (status) => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles').flush('', {
      status,
      statusText: 'Authentication required',
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Redirecting to sign in',
    );
    expect(assign).toHaveBeenCalledWith('/auth/login');
  });

  it('retries a 502 profile request from the unavailable state', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles').flush('Gateway failure details', {
      status: 502,
      statusText: 'Bad Gateway',
    });
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]')?.textContent;
    expect(alert).toContain('temporarily unavailable');
    expect(alert).not.toContain('Gateway failure details');

    const retryButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(retryButton.textContent).toContain('Retry');
    retryButton.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Loading integration profiles');
    http.expectOne('/bff/api/v1/integration-profiles').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No integration profiles are available');
  });
});
