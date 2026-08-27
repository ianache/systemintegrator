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
});
