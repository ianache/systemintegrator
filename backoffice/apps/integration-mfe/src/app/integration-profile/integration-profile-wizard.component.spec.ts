import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationProfileWizardComponent } from './integration-profile-wizard.component';

describe('IntegrationProfileWizardComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationProfileWizardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create() {
    const fixture = TestBed.createComponent(IntegrationProfileWizardComponent);
    fixture.detectChanges();
    return fixture;
  }

  function fill(fixture: ReturnType<typeof create>, selector: string, value: string) {
    const element = fixture.nativeElement.querySelector(selector) as HTMLInputElement | HTMLSelectElement;
    element.value = value;
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input'));
    fixture.detectChanges();
  }

  function clickNext(fixture: ReturnType<typeof create>) {
    (fixture.nativeElement.querySelector('[data-testid="wizard-next"]') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('blocks advancing past connectivity when protocol is set without connector and adapter', () => {
    const fixture = create();
    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture); // direction/SOT step, defaults are valid
    fill(fixture, '[name="protocol"]', 'KAFKA');
    clickNext(fixture);

    expect(fixture.nativeElement.textContent).toContain('connector y adapter son obligatorios');
  });

  it('submits the guided payload and emits created on success', () => {
    const fixture = create();
    const createdSpy = vi.fn();
    fixture.componentInstance.created.subscribe(createdSpy);

    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture);
    fill(fixture, '[name="protocol"]', 'KAFKA');
    fill(fixture, '[name="connector"]', 'sigo-kafka-connector');
    fill(fixture, '[name="adapter"]', 'SigoVehicleAdapter');
    clickNext(fixture);
    clickNext(fixture); // review step -> submits

    const request = http.expectOne('/bff/api/v1/integration-profiles');
    expect(request.request.body).toMatchObject({
      businessDomain: 'vehicle',
      externalSource: 'SIGO',
      protocol: 'KAFKA',
      connector: 'sigo-kafka-connector',
      adapter: 'SigoVehicleAdapter',
    });
    request.flush({ id: 'p-new', businessDomain: 'vehicle' });

    expect(createdSpy).toHaveBeenCalledWith(expect.objectContaining({ id: 'p-new' }));
  });

  it('shows the upstream conflict detail inline instead of closing on a 409', () => {
    const fixture = create();
    fill(fixture, '[name="businessDomain"]', 'vehicle');
    fill(fixture, '[name="externalSource"]', 'SIGO');
    clickNext(fixture);
    clickNext(fixture);
    clickNext(fixture);
    clickNext(fixture);

    const request = http.expectOne('/bff/api/v1/integration-profiles');
    request.flush(
      { detail: 'An active integration profile already exists for this domain and source' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('An active integration profile already exists');
  });
});
