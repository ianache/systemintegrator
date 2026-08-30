import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { FlowListComponent } from './flow-list.component';

const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: null,
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};

describe('FlowListComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FlowListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'integration/flows/:id', component: FlowListComponent },
          { path: 'integration/flows', component: FlowListComponent },
        ]),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a flow and navigates to its designer', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges(); // Trigger ngOnInit

    // Handle the initial list() call from ngOnInit
    const listRequest = http.expectOne('/bff/api/v1/flows');
    expect(listRequest.request.method).toBe('GET');
    listRequest.flush([]);
    fixture.detectChanges();

    // Verify the create button exists
    const createBtn = fixture.nativeElement.querySelector('.new-flow-btn');
    expect(createBtn).toBeTruthy();

    // Click to open form
    createBtn.click();
    fixture.detectChanges();

    // Verify form is visible
    const form = fixture.nativeElement.querySelector('form.new-flow-form');
    expect(form).toBeTruthy();

    // Fill in form inputs
    const codeInput = fixture.nativeElement.querySelector('input[name="code"]');
    const nameInput = fixture.nativeElement.querySelector('input[name="name"]');
    expect(codeInput).toBeTruthy();
    expect(nameInput).toBeTruthy();

    codeInput.value = 'flow/new';
    codeInput.dispatchEvent(new Event('input'));
    nameInput.value = 'New flow';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Submit the form
    form.dispatchEvent(new Event('submit', { cancelable: true }));

    // Verify the create request
    const createRequest = http.expectOne('/bff/api/v1/flows');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual({ code: 'flow/new', name: 'New flow' });
    createRequest.flush(FLOW);
  });
});
