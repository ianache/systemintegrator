import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CredentialSummary } from './credential.model';

@Injectable({ providedIn: 'root' })
export class CredentialService {
  private readonly http = inject(HttpClient);

  list(): Observable<CredentialSummary[]> {
    return this.http.get<CredentialSummary[]>('/bff/api/v1/credentials');
  }
}
