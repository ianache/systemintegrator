import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { FlowExecutionsComponent } from './flow-executions.component';
import { FlowExecutionSummary } from './flow.model';
import { CONSOLE_ROUTES } from '../console.routes';

const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: null,
  triggerSummary: 'CRON */5',
  activeVersionNumber: 1,
  status: 'PUBLISHED' as const,
  nodeCount: 3,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 1,
};

const EXECUTIONS: FlowExecutionSummary[] = [
  {
    id: 'e-1',
    flowId: 'f-1',
    flowVersionNumber: 1,
    status: 'SUCCESS',
    startedAt: '2026-08-30T10:00:00Z',
    finishedAt: '2026-08-30T10:00:01Z',
    durationMs: 900,
    errorMessage: null,
  },
  {
    id: 'e-2',
    flowId: 'f-1',
    flowVersionNumber: 1,
    status: 'FAILURE',
    startedAt: '2026-08-30T10:05:00Z',
    finishedAt: '2026-08-30T10:05:31Z',
    durationMs: 31000,
    errorMessage: 'connection refused',
  },
];

function setup() {
  TestBed.configureTestingModule({
    imports: [FlowExecutionsComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'integration', children: CONSOLE_ROUTES }]),
      {
        provide: ActivatedRoute,
        useValue: { paramMap: new BehaviorSubject(convertToParamMap({ flowId: 'f-1' })).asObservable() },
      },
    ],
  });
  return {
    http: TestBed.inject(HttpTestingController),
    fixture: TestBed.createComponent(FlowExecutionsComponent),
  };
}

describe('FlowExecutionsComponent', () => {
  it('lists executions and computes KPIs client-side', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions').flush(EXECUTIONS);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Alta de vehiculos');
    const values = fixture.nativeElement.querySelectorAll('.kpi-value');
    expect(values[0].textContent.trim()).toBe('2');
    expect(values[1].textContent.trim()).toBe('50%');
    http.verify();
  });

  it('filters to failures only', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions').flush(EXECUTIONS);
    fixture.detectChanges();

    fixture.componentInstance.setFilter('FAILURE');
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr.row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('e-2');
    http.verify();
  });

  it('navigates to an execution detail when a row is opened', async () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions').flush(EXECUTIONS);
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    fixture.componentInstance.open(EXECUTIONS[0]);
    await fixture.whenStable();

    expect(router.url).toBe('/integration/flows/f-1/executions/e-1');
    http.verify();
  });

  it('falls back to the unavailable state when the backend has no executions endpoint yet', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions').flush('not found', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Ejecuciones no disponibles');
    http.verify();
  });
});
