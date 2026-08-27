import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DeadLetterQueueService } from './dead-letter-queue.service';

describe('DeadLetterQueueService', () => {
  it('replays the dead letter queue through the BFF', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(DeadLetterQueueService);
    const http = TestBed.inject(HttpTestingController);

    service.replay().subscribe((summary) => expect(summary.total).toBe(3));
    const request = http.expectOne('/bff/api/v1/inbox/dlq/replay');
    expect(request.request.method).toBe('POST');
    request.flush({ total: 3, success: 2, failed: 1 });
    http.verify();
  });
});
