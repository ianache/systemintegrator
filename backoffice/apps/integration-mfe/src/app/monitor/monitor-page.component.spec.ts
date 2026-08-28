import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MonitorPageComponent } from './monitor-page.component';

describe('MonitorPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MonitorPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushInitialLoad(fixture: any, messages: unknown[] = []) {
    fixture.detectChanges();
    http.expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'ALL').flush(messages);
    fixture.detectChanges();
  }

  it('shows an empty state when there are no messages', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);
    expect(fixture.nativeElement.textContent).toContain('No hay mensajes para este filtro');
  });

  it('lists messages with their direction, event and status', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, [
      {
        id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
        status: 'DLQ', attempts: 1, lastError: 'boom', timestamp: '2026-08-20T10:00:00Z',
      },
    ]);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('units.upserted');
    expect(text).toContain('DLQ');
  });

  it('re-fetches with the selected status filter', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);

    const dlqChip = Array.from(fixture.nativeElement.querySelectorAll('.chip')).find(
      (el: any) => el.textContent.trim() === 'DLQ',
    ) as HTMLButtonElement;
    dlqChip.click();

    http.expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'DLQ').flush([]);
    fixture.detectChanges();
  });

  it('opens a message detail drawer and shows the payload', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, [
      {
        id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
        status: 'DLQ', attempts: 1, lastError: 'boom', timestamp: '2026-08-20T10:00:00Z',
      },
    ]);

    (fixture.nativeElement.querySelector('.messages-row') as HTMLElement).click();
    http.expectOne('/bff/api/v1/messages/INBOUND/evt-1').flush({
      id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
      status: 'DLQ', attempts: 1, lastError: 'boom', timestamp: '2026-08-20T10:00:00Z', payload: '{"a":1}',
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('{"a":1}');
  });

  it('retries a message from the drawer and refreshes the list', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, [
      {
        id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
        status: 'DLQ', attempts: 1, lastError: 'boom', timestamp: '2026-08-20T10:00:00Z',
      },
    ]);

    (fixture.nativeElement.querySelector('.messages-row') as HTMLElement).click();
    http.expectOne('/bff/api/v1/messages/INBOUND/evt-1').flush({
      id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
      status: 'DLQ', attempts: 1, lastError: 'boom', timestamp: '2026-08-20T10:00:00Z', payload: '{}',
    });
    fixture.detectChanges();

    const retryButton = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (el: any) => el.textContent.trim() === 'Reprocesar',
    ) as HTMLButtonElement;
    retryButton.click();

    http.expectOne('/bff/api/v1/messages/INBOUND/evt-1/retry').flush({
      id: 'evt-1', direction: 'INBOUND', eventType: 'units.upserted', domain: 'units',
      status: 'PENDING', attempts: 1, lastError: null, timestamp: '2026-08-20T10:00:00Z', payload: '{}',
    });
    http.expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'ALL').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.drawer-overlay')).toBeNull();
  });

  it('replays the DLQ and shows the real summary counts', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush({ total: 3, success: 2, failed: 1 });
    http.expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'ALL').flush([]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('total 3');
    expect(text).toContain('éxito 2');
    expect(text).toContain('fallidos 1');
  });

  it('shows an inline error when the replay call fails', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No se pudo ejecutar el reproceso');
  });
});
