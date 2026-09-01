import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { FlowListComponent } from './flow-list.component';
import { CONSOLE_ROUTES } from '../console.routes';
import { FlowDesignerComponent } from './flow-designer.component';
import { Flow } from './flow.model';

const FLOW: Flow = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: null,
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT' as const,
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
        // Mirror how the shell app mounts integration-mfe's CONSOLE_ROUTES under
        // '/integration' (see apps/shell/src/app/app.routes.ts), so this test
        // exercises the exact URLs the component navigates to.
        provideRouter([{ path: 'integration', children: CONSOLE_ROUTES }]),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a flow and navigates to its designer', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges(); // Trigger ngOnInit

    // Handle the initial list() call from ngOnInit (fired both by the component
    // and by the shared IntegrationTabsComponent's flow-count badge)
    const listRequests = http.match('/bff/api/v1/flows');
    expect(listRequests.length).toBe(2);
    listRequests.forEach((req) => {
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 0,
      executions24h: 0,
      errorRatePct: 0,
      p95DurationMs: null,
      p50DurationMs: null,
      lastRunStepCount: null,
      failedStepCount: 0,
    });
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

  it('navigates to the real flow designer route when a row is opened', async () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.match('/bff/api/v1/flows').forEach((req) => req.flush([]));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 0,
      executions24h: 0,
      errorRatePct: 0,
      p95DurationMs: null,
      p50DurationMs: null,
      lastRunStepCount: null,
      failedStepCount: 0,
    });
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    fixture.componentInstance.open({ ...FLOW });
    await fixture.whenStable();

    expect(router.url).toBe('/integration/flows/f-1');
    expect(
      CONSOLE_ROUTES[0].children?.some(
        (route) => route.path === 'flows/:flowId' && route.component === FlowDesignerComponent,
      ),
    ).toBe(true);
  });

  it('renders the KPI cards from the metrics summary', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.match('/bff/api/v1/flows').forEach((req) => req.flush([]));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 3,
      executions24h: 40,
      errorRatePct: 2.5,
      p95DurationMs: 810,
      p50DurationMs: 620,
      lastRunStepCount: 12,
      failedStepCount: 4,
    });
    fixture.detectChanges();

    const values = fixture.nativeElement.querySelectorAll('.kpi-value');
    expect(values[0].textContent.trim()).toBe('3');
    expect(values[1].textContent.trim()).toBe('40');
    expect(values[2].textContent.trim()).toContain('2.5');
    expect(values[3].textContent.trim()).toContain('810');

    const notes = fixture.nativeElement.querySelectorAll('.kpi-note');
    expect(notes[1].textContent.trim()).toBe('12 pasos en la última corrida');
    expect(notes[2].textContent.trim()).toBe('4 pasos con error');
    expect(notes[3].textContent.trim()).toBe('P50 620ms');
  });

  it('computes the published-flows note from draft/obsolete counts in the loaded flows', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.match('/bff/api/v1/flows').forEach((req) =>
      req.flush([
        { ...FLOW, id: 'f-1', status: 'PUBLISHED' },
        { ...FLOW, id: 'f-2', status: 'DRAFT' },
        { ...FLOW, id: 'f-3', status: 'OBSOLETE' },
      ]),
    );
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 1,
      executions24h: 0,
      errorRatePct: 0,
      p95DurationMs: null,
      p50DurationMs: null,
      lastRunStepCount: null,
      failedStepCount: 0,
    });
    fixture.detectChanges();

    const notes = fixture.nativeElement.querySelectorAll('.kpi-note');
    expect(notes[0].textContent.trim()).toBe('de 3 · 1 borrador, 1 obsoleto');
  });

  it('renders the row-level execution metrics and navigates to executions on Ver traza', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate');
    fixture.detectChanges();

    http.match('/bff/api/v1/flows').forEach((req) =>
      req.flush([{ ...FLOW, execs24h: 7, errorRatePct: 14.3, p95DurationMs: 250 }]),
    );
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 0,
      executions24h: 0,
      errorRatePct: 0,
      p95DurationMs: null,
      p50DurationMs: null,
      lastRunStepCount: null,
      failedStepCount: 0,
    });
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.flows-row');
    expect(row.textContent).toContain('7');
    expect(row.textContent).toContain('14.3%');
    expect(row.textContent).toContain('250ms');

    const traceBtn = Array.from(row.querySelectorAll('button')).find(
      (el: any) => el.textContent.trim() === 'Ver traza',
    ) as HTMLButtonElement;
    traceBtn.click();

    expect(navigateSpy).toHaveBeenCalledWith(['/integration/flows', 'f-1', 'executions']);
  });

  it('renders the page header before the tabs', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    http.match('/bff/api/v1/flows').forEach((req) => req.flush([]));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 0,
      executions24h: 0,
      errorRatePct: 0,
      p95DurationMs: null,
      p50DurationMs: null,
      lastRunStepCount: null,
      failedStepCount: 0,
    });
    fixture.detectChanges();

    const page = fixture.nativeElement.querySelector('section.page');
    const header = page.querySelector('.page-header');
    const tabs = page.querySelector('app-integration-tabs');
    expect(header.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows unavailable KPI cards when the metrics call fails', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.match('/bff/api/v1/flows').forEach((req) => req.flush([]));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush('error', {
      status: 500,
      statusText: 'Server Error',
    });
    fixture.detectChanges();

    const values = fixture.nativeElement.querySelectorAll('.kpi-value');
    expect(values[0].textContent.trim()).toBe('—');
    expect(fixture.nativeElement.querySelector('.state-message')).toBeFalsy();
  });
});
