import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateFlowPayload, Flow, FlowVersion, UpdateFlowDraftPayload } from './flow.model';

const BASE_URL = '/bff/api/v1/flows';

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
}
