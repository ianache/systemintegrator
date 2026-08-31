import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateFlowPayload,
  Flow,
  FlowExecutionSummary,
  FlowMetricsSummary,
  FlowVersion,
  ReportFlowExecutionPayload,
  TransformationPreviewResult,
  UpdateFlowDraftPayload,
} from './flow.model';

const BASE_URL = '/bff/api/v1/flows';
const TRANSFORMATIONS_URL = '/bff/api/v1/transformations';

@Injectable({ providedIn: 'root' })
export class FlowService {
  private readonly http = inject(HttpClient);

  list(): Observable<Flow[]> {
    return this.http.get<Flow[]>(BASE_URL);
  }

  get(id: string): Observable<Flow> {
    return this.http.get<Flow>(`${BASE_URL}/${id}`);
  }

  create(payload: CreateFlowPayload): Observable<Flow> {
    return this.http.post<Flow>(BASE_URL, payload);
  }

  updateDraft(id: string, payload: UpdateFlowDraftPayload): Observable<Flow> {
    return this.http.put<Flow>(`${BASE_URL}/${id}`, payload);
  }

  listVersions(flowId: string): Observable<FlowVersion[]> {
    return this.http.get<FlowVersion[]>(`${BASE_URL}/${flowId}/versions`);
  }

  publish(flowId: string): Observable<FlowVersion> {
    return this.http.post<FlowVersion>(`${BASE_URL}/${flowId}/versions/publish`, {});
  }

  rollback(flowId: string, versionNumber: number): Observable<FlowVersion> {
    return this.http.post<FlowVersion>(`${BASE_URL}/${flowId}/versions/${versionNumber}/rollback`, {});
  }

  archive(flowId: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${flowId}`);
  }

  getMetricsSummary(): Observable<FlowMetricsSummary> {
    return this.http.get<FlowMetricsSummary>(`${BASE_URL}/metrics/summary`);
  }

  reportExecution(flowId: string, payload: ReportFlowExecutionPayload): Observable<unknown> {
    return this.http.post(`${BASE_URL}/${flowId}/executions`, payload);
  }

  /**
   * FlowController only exposes POST .../executions (reportExecution) today —
   * there is no GET list/detail endpoint yet. These follow the same path
   * convention so the execution views light up the moment the backend adds
   * them; until then they 404 and the views fall back to their "no
   * disponible" empty state, same as the flow designer does for other
   * not-yet-implemented backend pieces.
   */
  listExecutions(flowId: string): Observable<FlowExecutionSummary[]> {
    return this.http.get<FlowExecutionSummary[]>(`${BASE_URL}/${flowId}/executions`);
  }

  getExecution(flowId: string, executionId: string): Observable<FlowExecutionSummary> {
    return this.http.get<FlowExecutionSummary>(`${BASE_URL}/${flowId}/executions/${executionId}`);
  }

  /** Real backend preview for a standalone script — see TransformationController. */
  previewTransformation(engine: string, script: string, payload: string): Observable<TransformationPreviewResult> {
    return this.http.post<TransformationPreviewResult>(`${TRANSFORMATIONS_URL}/preview`, { engine, script, payload });
  }
}
