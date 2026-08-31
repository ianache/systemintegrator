import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { CONSOLE_ROUTES } from '../console.routes';
import { FlowExecutionDetailComponent } from './flow-execution-detail.component';
import { FlowExecutionDetail } from './flow.model';

const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: {
    nodes: [
      { id: 'n1', type: 'JDBC_SOURCE', name: 'SIGO', x: 0, y: 0 },
      { id: 'n2', type: 'TRANSFORM_JSLT', name: 'Normalizar', x: 240, y: 0 },
    ],
    edges: [{ from: 'n1', to: 'n2' }],
  },
  triggerSummary: 'CRON */5',
  activeVersionNumber: 1,
  status: 'PUBLISHED' as const,
  nodeCount: 2,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 1,
};

const EXECUTION: FlowExecutionDetail = {
  id: 'e-1',
  flowId: 'f-1',
  flowVersionNumber: 1,
  status: 'FAILURE',
  startedAt: '2026-08-30T10:05:00Z',
  finishedAt: '2026-08-30T10:05:31Z',
  durationMs: 31000,
  errorMessage: 'connection refused',
  steps: [],
};

function setup() {
  TestBed.configureTestingModule({
    imports: [FlowExecutionDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'integration', children: CONSOLE_ROUTES }]),
      {
        provide: ActivatedRoute,
        useValue: {
          paramMap: new BehaviorSubject(convertToParamMap({ flowId: 'f-1', executionId: 'e-1' })).asObservable(),
        },
      },
    ],
  });
  return {
    http: TestBed.inject(HttpTestingController),
    fixture: TestBed.createComponent(FlowExecutionDetailComponent),
  };
}

describe('FlowExecutionDetailComponent', () => {
  it('loads the execution and renders its graph read-only', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions/e-1').flush(EXECUTION);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.exec-id').textContent).toContain('e-1');
    expect(fixture.nativeElement.querySelectorAll('.graph-node').length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('connection refused');
    http.verify();
  });

  it('switches to the timeline mode and shows the disabled replay button', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions/e-1').flush(EXECUTION);
    fixture.detectChanges();

    fixture.componentInstance.setMode('TIMELINE');
    fixture.detectChanges();

    const replayBtn = fixture.nativeElement.querySelector('.replay-btn') as HTMLButtonElement;
    expect(replayBtn).toBeTruthy();
    expect(replayBtn.disabled).toBe(true);
    http.verify();
  });

  it('navigates back to the executions list', async () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions/e-1').flush(EXECUTION);
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    (fixture.nativeElement.querySelector('.back-link') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(router.url).toBe('/integration/flows/f-1/executions');
    http.verify();
  });

  it('falls back to the unavailable state when the execution cannot be fetched', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
    http.expectOne('/bff/api/v1/flows/f-1/executions/e-1').flush('not found', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Ejecución no disponible');
    http.verify();
  });
});
