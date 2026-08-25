import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileService } from './integration-profile.service';

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

    const request = http.expectOne('/bff/api/v1/integration-profiles');
    expect(request.request.method).toBe('GET');
    request.flush([
      {
        id: 'p-1',
        businessDomain: 'orders',
        externalSource: 'erp',
        syncDirection: 'INBOUND',
        active: true,
        version: 0,
      },
    ]);
  });
});
