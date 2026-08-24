import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { IntegrationProfile } from './integration-profile.model';

@Injectable({ providedIn: 'root' })
export class IntegrationProfileService {
  private readonly http = inject(HttpClient);

  list(): Observable<IntegrationProfile[]> {
    return this.http.get<IntegrationProfile[]>('/bff/api/v1/integration-profiles');
  }
}
