import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateIntegrationProfilePayload,
  IntegrationProfile,
  TriggerSyncResult,
  UpdateIntegrationProfilePayload,
} from './integration-profile.model';

const BASE_URL = '/bff/api/v1/integration-profiles';

@Injectable({ providedIn: 'root' })
export class IntegrationProfileService {
  private readonly http = inject(HttpClient);

  list(activeOnly = true): Observable<IntegrationProfile[]> {
    const params = new HttpParams().set('activeOnly', String(activeOnly));
    return this.http.get<IntegrationProfile[]>(BASE_URL, { params });
  }

  get(id: string): Observable<IntegrationProfile> {
    return this.http.get<IntegrationProfile>(`${BASE_URL}/${id}`);
  }

  create(payload: CreateIntegrationProfilePayload): Observable<IntegrationProfile> {
    return this.http.post<IntegrationProfile>(BASE_URL, payload);
  }

  update(id: string, payload: UpdateIntegrationProfilePayload): Observable<IntegrationProfile> {
    return this.http.put<IntegrationProfile>(`${BASE_URL}/${id}`, payload);
  }

  deactivate(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${id}`);
  }

  triggerSync(id: string): Observable<TriggerSyncResult> {
    return this.http.post<TriggerSyncResult>(`${BASE_URL}/${id}/sync`, {});
  }
}
