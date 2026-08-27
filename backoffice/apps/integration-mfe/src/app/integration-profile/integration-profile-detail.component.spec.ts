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
});
