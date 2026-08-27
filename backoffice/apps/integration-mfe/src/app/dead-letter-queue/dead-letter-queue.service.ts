import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DlqReplaySummary } from './dead-letter-queue.model';

@Injectable({ providedIn: 'root' })
export class DeadLetterQueueService {
  private readonly http = inject(HttpClient);

  replay(): Observable<DlqReplaySummary> {
    return this.http.post<DlqReplaySummary>('/bff/api/v1/inbox/dlq/replay', {});
  }
}
