import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MessageDetail, MessageDirection, MessageStatusFilter, MessageSummary } from './message-monitor.model';

@Injectable({ providedIn: 'root' })
export class MessageMonitorService {
  private readonly http = inject(HttpClient);

  list(status: MessageStatusFilter): Observable<MessageSummary[]> {
    return this.http.get<MessageSummary[]>('/bff/api/v1/messages', { params: { status } });
  }

  get(direction: MessageDirection, id: string): Observable<MessageDetail> {
    return this.http.get<MessageDetail>(`/bff/api/v1/messages/${direction}/${id}`);
  }

  retry(direction: MessageDirection, id: string): Observable<MessageDetail> {
    return this.http.post<MessageDetail>(`/bff/api/v1/messages/${direction}/${id}/retry`, {});
  }

  moveToDlq(direction: MessageDirection, id: string): Observable<MessageDetail> {
    return this.http.post<MessageDetail>(`/bff/api/v1/messages/${direction}/${id}/dlq`, {});
  }
}
