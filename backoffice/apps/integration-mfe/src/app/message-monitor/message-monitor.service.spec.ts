import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageMonitorService } from './message-monitor.service';

describe('MessageMonitorService', () => {
  let service: MessageMonitorService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(MessageMonitorService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists messages filtered by status', () => {
    service.list('DLQ').subscribe((messages) => expect(messages.length).toBe(1));

    const request = http.expectOne((req) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'DLQ');
    expect(request.request.method).toBe('GET');
    request.flush([{ id: 'm1', status: 'DLQ' }]);
  });

  it('gets a single message by direction and id', () => {
    service.get('INBOUND', 'm1').subscribe((message) => expect(message.id).toBe('m1'));

    const request = http.expectOne('/bff/api/v1/messages/INBOUND/m1');
    expect(request.request.method).toBe('GET');
    request.flush({ id: 'm1' });
  });

  it('retries a message', () => {
    service.retry('OUTBOUND', 'm1').subscribe((message) => expect(message.status).toBe('PENDING'));

    const request = http.expectOne('/bff/api/v1/messages/OUTBOUND/m1/retry');
    expect(request.request.method).toBe('POST');
    request.flush({ id: 'm1', status: 'PENDING' });
  });

  it('moves a message to the DLQ', () => {
    service.moveToDlq('OUTBOUND', 'm1').subscribe((message) => expect(message.status).toBe('DLQ'));

    const request = http.expectOne('/bff/api/v1/messages/OUTBOUND/m1/dlq');
    expect(request.request.method).toBe('POST');
    request.flush({ id: 'm1', status: 'DLQ' });
  });
});
