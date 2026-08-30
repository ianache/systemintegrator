import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FlowService } from './flow.service';

const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: { nodes: [] },
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};

const FLOW_VERSION = {
  id: 'v-1',
  flowId: 'f-1',
  versionNumber: 1,
  graph: { nodes: [] },
  state: 'ACTIVE',
  publishedBy: 'user@tenant',
  publishedAt: '2026-08-30T00:00:00Z',
};

describe('FlowService', () => {
  let service: FlowService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(FlowService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists flows through the BFF same-origin endpoint', () => {
    service.list().subscribe((flows) => expect(flows[0].id).toBe('f-1'));
    const request = http.expectOne('/bff/api/v1/flows');
    expect(request.request.method).toBe('GET');
    request.flush([FLOW]);
  });

  it('gets a single flow by id', () => {
    service.get('f-1').subscribe((flow) => expect(flow.code).toBe('flow/vehiculo-alta'));
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
  });

  it('creates a flow', () => {
    service.create({ code: 'flow/x', name: 'X' }).subscribe((flow) => expect(flow.id).toBe('f-1'));
    const request = http.expectOne('/bff/api/v1/flows');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ code: 'flow/x', name: 'X' });
    request.flush(FLOW);
  });

  it('updates the draft with the expected version', () => {
    service
      .updateDraft('f-1', { name: 'X renamed', triggerSummary: 'CRON */5', draftGraph: { nodes: [] }, expectedVersion: 0 })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(0);
    request.flush(FLOW);
  });

  it('lists versions for a flow', () => {
    service.listVersions('f-1').subscribe((versions) => expect(versions[0].versionNumber).toBe(1));
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([FLOW_VERSION]);
  });

  it('publishes the current draft', () => {
    service.publish('f-1').subscribe((version) => expect(version.state).toBe('ACTIVE'));
    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/publish');
    expect(request.request.method).toBe('POST');
    request.flush(FLOW_VERSION);
  });

  it('rolls back to an earlier version', () => {
    service.rollback('f-1', 1).subscribe((version) => expect(version.versionNumber).toBe(1));
    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/1/rollback');
    expect(request.request.method).toBe('POST');
    request.flush(FLOW_VERSION);
  });

  it('archives a flow', () => {
    service.archive('f-1').subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('gets the flow metrics summary', () => {
    service.getMetricsSummary().subscribe((summary) => expect(summary.publishedFlowCount).toBe(3));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 3,
      executions24h: 40,
      errorRatePct: 2.5,
      p95DurationMs: 810,
    });
  });

  it('reports a flow execution', () => {
    service
      .reportExecution('f-1', {
        flowVersionNumber: 1,
        status: 'SUCCESS',
        startedAt: '2026-08-30T00:00:00Z',
        finishedAt: '2026-08-30T00:00:01Z',
      })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1/executions');
    expect(request.request.method).toBe('POST');
    request.flush({});
  });
});
