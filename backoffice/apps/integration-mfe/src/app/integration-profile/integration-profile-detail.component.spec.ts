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
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
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

  function openMapTab(fixture: any, transformation: unknown = null): void {
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
      ...FULL_PROFILE,
      configuration: { protocol: null, connector: null, adapter: null, endpoint: null, credentialRef: null, mapping: null, transformation, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null, extractionConfig: null },
    });
    fixture.detectChanges();

    const mapTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'Mapping & Transformation',
    ) as HTMLButtonElement;
    mapTabButton.click();
    fixture.detectChanges();
  }

  it('detects FIELD_MAPPING from the saved config and renders its rules table', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture, {
      engine: 'FIELD_MAPPING',
      fields: [{ target: 'vin', sourcePath: '$.Vehiculo.Chasis', transform: '', type: 'STRING', defaultValue: '', required: true }],
    });

    const activeChip = fixture.nativeElement.querySelector('.chip.active');
    expect(activeChip.textContent.trim()).toBe('FIELD_MAPPING');
    const targetInput = fixture.nativeElement.querySelector('[data-testid="mapping-row-target"]') as HTMLInputElement;
    expect(targetInput.value).toBe('vin');
    const sourcePathInput = fixture.nativeElement.querySelector('[data-testid="mapping-row-source-path"]') as HTMLInputElement;
    expect(sourcePathInput.value).toBe('$.Vehiculo.Chasis');
    const typeInput = fixture.nativeElement.querySelector('[data-testid="mapping-row-type"]') as HTMLInputElement;
    expect(typeInput.value).toBe('STRING');
  });

  it('edits target, source path, transform, type and default value by typing (input event, not blur)', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture, {
      engine: 'FIELD_MAPPING',
      fields: [{ target: 'vin', sourcePath: '$.old', transform: '', type: 'STRING', defaultValue: '', required: false }],
    });

    const setValue = (testId: string, value: string) => {
      const input = fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };

    setValue('mapping-row-target', 'chassisNumber');
    expect((fixture.nativeElement.querySelector('[data-testid="mapping-row-target"]') as HTMLInputElement).value).toBe('chassisNumber');

    setValue('mapping-row-source-path', '$.new.path');
    expect((fixture.nativeElement.querySelector('[data-testid="mapping-row-source-path"]') as HTMLInputElement).value).toBe('$.new.path');

    setValue('mapping-row-transform', "#val.toUpperCase()");
    expect((fixture.nativeElement.querySelector('[data-testid="mapping-row-transform"]') as HTMLInputElement).value).toBe("#val.toUpperCase()");

    setValue('mapping-row-type', 'NUMBER');
    expect((fixture.nativeElement.querySelector('[data-testid="mapping-row-type"]') as HTMLInputElement).value).toBe('NUMBER');

    setValue('mapping-row-default', '0');
    expect((fixture.nativeElement.querySelector('[data-testid="mapping-row-default"]') as HTMLInputElement).value).toBe('0');
  });

  it('adds and removes field-mapping rows', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture, { engine: 'FIELD_MAPPING', fields: [] });

    fixture.nativeElement.querySelector('.mapping-add-btn').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.mapping-row').length).toBe(1);

    fixture.nativeElement.querySelector('.mapping-remove').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.mapping-row').length).toBe(0);
  });

  it('switches to JSLT and edits the script', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture);

    const jsltChip = Array.from(fixture.nativeElement.querySelectorAll('.chip')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'JSLT',
    ) as HTMLButtonElement;
    jsltChip.click();
    fixture.detectChanges();

    const scriptArea = fixture.nativeElement.querySelector('[data-testid="jslt-script"]') as HTMLTextAreaElement;
    scriptArea.value = '{ "vin": .Vehiculo.Chasis }';
    scriptArea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('El payload de origen se entrega sin alteración');
  });

  it('shows the passthrough explanation when no engine is configured', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture);

    expect(fixture.nativeElement.textContent).toContain('El payload de origen se entrega sin alteración');
  });

  function openPolicyTab(fixture: any, configuration: Record<string, unknown> = {}): void {
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
      ...FULL_PROFILE,
      configuration: {
        protocol: 'REST', connector: 'connector', adapter: 'adapter', endpoint: 'https://example.test',
        credentialRef: null, mapping: null, transformation: null,
        syncPolicy: { mode: 'EVENT_DRIVEN', trigger: 'vehicle.upserted.v1', batchSize: 200 },
        retryPolicy: { maxAttempts: 5, backoff: 'EXPONENTIAL', initialIntervalMs: 2000 },
        rateLimitPolicy: { requestsPerSecond: 25, burst: 50 }, extractionConfig: null,
        ...configuration,
      },
    });
    fixture.detectChanges();
    (fixture.nativeElement.querySelectorAll('.tab')[3] as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('renders the visual policy controls from the saved policy objects', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openPolicyTab(fixture);

    expect(fixture.nativeElement.querySelector('[data-testid="sync-mode"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="sync-trigger"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="sync-batch-size"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="retry-max-attempts"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="retry-backoff"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="rate-requests-per-second"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="rate-burst"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="circuit-breaker-status"]')?.textContent.trim()).toBe('CLOSED');
    expect(fixture.nativeElement.querySelectorAll('textarea[name$="PolicyJson"]').length).toBe(0);
  });

  it('updates the policy JSON values through the visual controls before saving', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openPolicyTab(fixture);

    const attempts = fixture.nativeElement.querySelector('[data-testid="retry-max-attempts"]') as HTMLInputElement;
    attempts.value = '3';
    attempts.dispatchEvent(new Event('input'));
    const backoff = fixture.nativeElement.querySelector('[data-testid="retry-backoff"]') as HTMLSelectElement;
    backoff.value = 'FIXED';
    backoff.dispatchEvent(new Event('change'));
    const requests = fixture.nativeElement.querySelector('[data-testid="rate-requests-per-second"]') as HTMLInputElement;
    requests.value = '10';
    requests.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="save-profile"]') as HTMLButtonElement).click();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
    expect(request.request.body.retryPolicy).toMatchObject({ maxAttempts: 3, backoff: 'FIXED' });
    expect(request.request.body.rateLimitPolicy).toMatchObject({ requestsPerSecond: 10, burst: 50 });
    request.flush(FULL_PROFILE);
  });

  it('runs a real dry-run and shows the transformation output', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture);

    fixture.nativeElement.querySelector('[data-testid="run-dry-run"]').click();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/mapping/dry-run');
    expect(request.request.body.transformationJson).toContain('PASSTHROUGH');
    request.flush({ output: '{"vin":"1"}', error: null });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.mapping-result-pre').textContent).toContain('"vin"');
  });

  it('shows the real transformation error when a dry-run fails', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openMapTab(fixture);

    fixture.nativeElement.querySelector('[data-testid="run-dry-run"]').click();
    http.expectOne('/bff/api/v1/integration-profiles/p-1/mapping/dry-run')
      .flush({ output: null, error: "Required field 'vin' missing from source path: $.vin" });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain("Required field 'vin' missing from source path: $.vin");
  });

  it('computes the projected retry sequence from the visual retry controls', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    openPolicyTab(fixture, { retryPolicy: { maxAttempts: 4, backoff: 'EXPONENTIAL', initialIntervalMs: 2000 } });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2000ms');
    expect(fixture.nativeElement.textContent).toContain('4000ms');
    expect(fixture.nativeElement.textContent).toContain('8000ms');
  });

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

  it('pauses an active profile', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    const pauseBtn = fixture.nativeElement.querySelector('[data-testid="pause-profile"]');
    pauseBtn.click();

    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/pause');
    expect(request.request.method).toBe('POST');
    request.flush({ ...FULL_PROFILE, paused: true, status: 'PAUSED' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="pause-profile"]').textContent.trim()).toBe('Reanudar');
  });

  it('does not deactivate when the confirmation is declined', () => {
    confirmSpy.mockReturnValue(false);
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="deactivate-profile"]') as HTMLButtonElement).click();
    http.expectNone('/bff/api/v1/integration-profiles/p-1');
  });

  it('only shows the Extracción SQL tab for JDBC profiles, with the probe disabled (no backend dry-run yet)', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
      ...FULL_PROFILE,
      configuration: {
        protocol: 'JDBC', connector: 'sigo-jdbc-connector', adapter: 'SigoVehicleAdapter', endpoint: null, credentialRef: null,
        mapping: null, transformation: null, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null,
        extractionConfig: { query: 'SELECT 1', watermarkColumn: 'updated_at', watermarkParam: 'lastSyncWithBuffer', keyColumn: 'id', fetchSize: 500, batchMode: false, batchSize: 500 },
      },
    });
    fixture.detectChanges();

    const extractTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'Extracción SQL',
    ) as HTMLButtonElement | undefined;
    expect(extractTabButton).toBeTruthy();

    extractTabButton!.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="tab-extract"]')).not.toBeNull();
    const queryEditor = fixture.nativeElement.querySelector('[data-testid="extract-query"]') as HTMLTextAreaElement;
    expect(queryEditor.value).toBe('SELECT 1');

    const probeBtn = fixture.nativeElement.querySelector('.extract-actions button') as HTMLButtonElement;
    expect(probeBtn.disabled).toBe(true);
  });

  it('hides the Extracción SQL tab for non-JDBC profiles', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush({
      ...FULL_PROFILE,
      configuration: { protocol: 'REST', connector: 'sap-rest-connector', adapter: 'SapAdapter', endpoint: null, credentialRef: null, mapping: null, transformation: null, syncPolicy: null, retryPolicy: null, rateLimitPolicy: null, extractionConfig: null },
    });
    fixture.detectChanges();

    const extractTabButton = Array.from(fixture.nativeElement.querySelectorAll('.tab')).find(
      (el) => (el as HTMLElement).textContent?.trim() === 'Extracción SQL',
    );
    expect(extractTabButton).toBeUndefined();
  });
});
