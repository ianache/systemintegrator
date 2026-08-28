import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileService } from './integration-profile.service';

const FULL_PROFILE = {
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: {
    protocol: 'KAFKA',
    connector: 'sigo-kafka-connector',
    adapter: 'SigoVehicleAdapter',
    endpoint: 'kafka://sigo-prod/vehiculos.v1',
    credentialRef: 'vault://tenant-a/sigo/kafka-sasl',
    mapping: null,
    transformation: null,
    syncPolicy: null,
    retryPolicy: { maxAttempts: 5, backoff: 'EXPONENTIAL' },
    rateLimitPolicy: null,
    extractionConfig: null,
  },
  active: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 7,
};

describe('IntegrationProfileService', () => {
  let service: IntegrationProfileService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(IntegrationProfileService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads profiles through the BFF same-origin endpoint', () => {
    service.list().subscribe((profiles) => expect(profiles[0].id).toBe('p-1'));
    const request = http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true');
    expect(request.request.method).toBe('GET');
    request.flush([FULL_PROFILE]);
  });

  it('loads all profiles (including inactive) when activeOnly is false', () => {
    service.list(false).subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false');
    request.flush([FULL_PROFILE]);
  });

  it('loads a single profile by id', () => {
    service.get('p-1').subscribe((profile) => expect(profile.businessDomain).toBe('vehicle'));
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
  });

  it('creates a profile', () => {
    service
      .create({ businessDomain: 'vehicle', externalSource: 'SIGO', syncDirection: 'INBOUND', sourceOfTruth: 'EXTERNAL' })
      .subscribe((profile) => expect(profile.id).toBe('p-1'));
    const request = http.expectOne('/bff/api/v1/integration-profiles');
    expect(request.request.method).toBe('POST');
    request.flush(FULL_PROFILE);
  });

  it('updates a profile with the expected version', () => {
    service
      .update('p-1', {
        businessDomain: 'vehicle',
        externalSource: 'SIGO',
        syncDirection: 'INBOUND',
        sourceOfTruth: 'EXTERNAL',
        expectedVersion: 7,
      })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(7);
    request.flush(FULL_PROFILE);
  });

  it('deactivates a profile', () => {
    service.deactivate('p-1').subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('triggers a sync', () => {
    service.triggerSync('p-1').subscribe((result) => expect(result.status).toBe('TRIGGERED'));
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/sync');
    expect(request.request.method).toBe('POST');
    request.flush({ profileId: 'p-1', status: 'TRIGGERED', triggeredAt: '2026-08-26T00:00:00Z' });
  });

  it('runs a mapping dry-run', () => {
    service.mappingDryRun('p-1', '{"a":1}', '{"engine":"PASSTHROUGH"}').subscribe((result) => expect(result.output).toBe('{"a":1}'));
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/mapping/dry-run');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ payload: '{"a":1}', transformationJson: '{"engine":"PASSTHROUGH"}' });
    request.flush({ output: '{"a":1}', error: null });
  });
});
