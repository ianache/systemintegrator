import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConnectorsPageComponent } from './connectors-page.component';

describe('ConnectorsPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ConnectorsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flush(fixture: any, profiles: unknown[]) {
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush(profiles);
    fixture.detectChanges();
  }

  it('renders the heading', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    flush(fixture, []);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Conectores y adapters');
  });

  it('shows an empty state when no profile declares a connector', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    flush(fixture, []);
    expect(fixture.nativeElement.textContent).toContain('No hay conectores registrados todavía');
  });

  it('groups profiles by connector, listing distinct adapters and usage counts', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    flush(fixture, [
      {
        id: 'p1', configuration: { protocol: 'KAFKA', connector: 'sigo-kafka-connector', adapter: 'SigoVehicleAdapter' },
        active: true,
      },
      {
        id: 'p2', configuration: { protocol: 'KAFKA', connector: 'sigo-kafka-connector', adapter: 'SigoBrandAdapter' },
        active: false,
      },
      {
        id: 'p3', configuration: { protocol: 'REST', connector: 'sap-rest-connector', adapter: 'SapCustomerAdapter' },
        active: true,
      },
    ]);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('sigo-kafka-connector');
    expect(text).toContain('SigoVehicleAdapter');
    expect(text).toContain('SigoBrandAdapter');
    expect(text).toContain('2 profiles de integración');
    expect(text).toContain('1 activo');
    expect(text).toContain('sap-rest-connector');
    expect(text).toContain('EN USO');

    const cards = fixture.nativeElement.querySelectorAll('.connector-card');
    expect(cards.length).toBe(2);
  });

  it('ignores profiles without a declared connector', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    flush(fixture, [{ id: 'p1', configuration: { protocol: 'JDBC', connector: null, adapter: null }, active: true }]);
    expect(fixture.nativeElement.textContent).toContain('No hay conectores registrados todavía');
  });

  it('marks a connector with only inactive profiles as not in use', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    flush(fixture, [
      { id: 'p1', configuration: { protocol: 'SOAP', connector: 'sap-soap-connector', adapter: 'SapSoapOrderAdapter' }, active: false },
    ]);
    expect(fixture.nativeElement.textContent).toContain('SIN USO ACTIVO');
  });

  it('shows an unavailable state with retry when the request fails', () => {
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('no está disponible temporalmente');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush([]);
  });
});
