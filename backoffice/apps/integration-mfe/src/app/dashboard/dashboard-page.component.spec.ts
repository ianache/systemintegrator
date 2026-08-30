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
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
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
      profile({ id: 'p3', active: false, status: 'INACTIVE', businessDomain: 'customer', externalSource: 'SAP', syncDirection: 'BIDIRECTIONAL', sourceOfTruth: 'SHARED' }),
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('2'); // active count
    expect(text).toContain('1'); // inactive count
    expect(text).toContain('No disponible');
  });

  it('includes paused and error profiles in the attention list even when active', () => {
    const fixture = TestBed.createComponent(DashboardPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush([
      profile({ id: 'p1', active: true, status: 'PAUSED', businessDomain: 'vehicle-model', externalSource: 'SIGO' }),
      profile({ id: 'p2', active: true, status: 'ERROR', businessDomain: 'customer', externalSource: 'SAP' }),
      profile({ id: 'p3', active: true, status: 'DRAFT', businessDomain: 'waybill', externalSource: 'TMS' }),
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('vehicle-model');
    expect(text).toContain('customer');
    expect(text).not.toContain('waybill');
  });
});
