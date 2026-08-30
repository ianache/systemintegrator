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
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
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

  it('shows a session-expired state and redirects to login for a 401 response', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush('', {
      status: 401,
      statusText: 'Authentication required',
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('sesión');
    expect(assign).toHaveBeenCalledWith('/auth/login');
  });

  it('shows a forbidden state without redirecting to login for a 403 response', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush('', {
      status: 403,
      statusText: 'Forbidden',
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Gateway');
    expect(assign).not.toHaveBeenCalled();
  });

  it('retries a 502 profile request from the unavailable state', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush('Gateway failure details', {
      status: 502,
      statusText: 'Bad Gateway',
    });
    fixture.detectChanges();

    const retryButton = Array.from(fixture.nativeElement.querySelectorAll('button.btn')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'Reintentar',
    ) as HTMLButtonElement;
    expect(retryButton).not.toBeUndefined();
    retryButton.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cargando integration profiles');
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([]);
  });

  it('opens the create wizard from the toolbar button', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.new-profile-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-integration-profile-wizard')).not.toBeNull();
  });

  it('renders the shared Integraciones header and the Última sync column', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([
      profile({ status: 'ERROR', lastSyncAt: null }),
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Integraciones');
    const statusCell = fixture.nativeElement.querySelector('.badge.error');
    expect(statusCell).toBeTruthy();
    expect(statusCell.textContent).toContain('Con error');
    const lastSyncCell = fixture.nativeElement.querySelectorAll('tbody td')[6];
    expect(lastSyncCell.textContent.trim()).toBe('—');
  });
});
