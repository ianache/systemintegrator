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

  function flushInitialLoad(fixture: any, messages: unknown[] = [], profiles: unknown[] = []) {
    fixture.detectChanges();
    http.expectOne((req: any) => req.url === '/bff/api/v1/integration-profiles').flush(profiles);
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

  it('populates the PROFILE dropdown from active profiles and refetches on selection', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, [], [
      { id: 'p1', businessDomain: 'units' },
      { id: 'p2', businessDomain: 'vehicles' },
      { id: 'p3', businessDomain: 'units' },
    ]);

    const select = fixture.nativeElement.querySelector('[data-testid="monitor-domain-filter"]') as HTMLSelectElement;
    const optionValues = Array.from(select.querySelectorAll('option')).map((o: any) => o.value);
    expect(optionValues).toEqual(['', 'units', 'vehicles']);

    select.value = 'units';
    select.dispatchEvent(new Event('change'));

    http
      .expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('domain') === 'units')
      .flush([]);
    fixture.detectChanges();
  });

  it('re-fetches with ISO from/to bounds when DESDE/HASTA are set', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);

    const fromInput = fixture.nativeElement.querySelector('[data-testid="monitor-date-from"]') as HTMLInputElement;
    fromInput.value = '2026-08-01';
    fromInput.dispatchEvent(new Event('change'));

    http
      .expectOne(
        (req: any) => req.url === '/bff/api/v1/messages' && req.params.get('from') === '2026-08-01T00:00:00.000Z',
      )
      .flush([]);
    fixture.detectChanges();

    const toInput = fixture.nativeElement.querySelector('[data-testid="monitor-date-to"]') as HTMLInputElement;
    toInput.value = '2026-08-31';
    toInput.dispatchEvent(new Event('change'));

    http
      .expectOne(
        (req: any) =>
          req.url === '/bff/api/v1/messages' &&
          req.params.get('from') === '2026-08-01T00:00:00.000Z' &&
          req.params.get('to') === '2026-08-31T23:59:59.999Z',
      )
      .flush([]);
    fixture.detectChanges();
  });

  it('clears date filters and reloads without from/to params', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, []);

    const fromInput = fixture.nativeElement.querySelector('[data-testid="monitor-date-from"]') as HTMLInputElement;
    fromInput.value = '2026-08-01';
    fromInput.dispatchEvent(new Event('change'));
    http
      .expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('from') === '2026-08-01T00:00:00.000Z')
      .flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="monitor-clear-dates"]') as HTMLButtonElement).click();

    http
      .expectOne(
        (req: any) =>
          req.url === '/bff/api/v1/messages' && !req.params.has('from') && !req.params.has('to'),
      )
      .flush([]);
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('[data-testid="monitor-date-from"]') as HTMLInputElement).value).toBe('');
    expect((fixture.nativeElement.querySelector('[data-testid="monitor-date-to"]') as HTMLInputElement).value).toBe('');
  });

  function makeMessages(count: number) {
    return Array.from({ length: count }, (_, i) => ({
      id: `evt-${i}`,
      direction: 'INBOUND',
      eventType: 'units.upserted',
      domain: 'units',
      status: 'PROCESSED',
      attempts: 0,
      lastError: null,
      timestamp: `2026-08-20T10:${String(i).padStart(2, '0')}:00Z`,
    }));
  }

  it('paginates messages using the default page size and shows page info', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, makeMessages(45));

    const rows = fixture.nativeElement.querySelectorAll('.messages-row');
    expect(rows.length).toBe(20);
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 1 de 3');
  });

  it('changing the page size resets to page 1 and re-slices the rows', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, makeMessages(45));

    const select = fixture.nativeElement.querySelector('[data-testid="monitor-page-size"]') as HTMLSelectElement;
    select.value = '10';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.messages-row').length).toBe(10);
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 1 de 5');
  });

  it('navigates pages with next/prev and numbered buttons', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, makeMessages(45));

    (fixture.nativeElement.querySelector('[data-testid="monitor-page-next"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 2 de 3');

    const thirdPageBtn = Array.from(fixture.nativeElement.querySelectorAll('.page-btn')).find(
      (el: any) => el.textContent.trim() === '3',
    ) as HTMLButtonElement;
    thirdPageBtn.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 3 de 3');
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-next"]').disabled).toBe(true);

    (fixture.nativeElement.querySelector('[data-testid="monitor-page-prev"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 2 de 3');
  });

  it('resets to page 1 when a filter changes', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    flushInitialLoad(fixture, makeMessages(45));

    (fixture.nativeElement.querySelector('[data-testid="monitor-page-next"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 2 de 3');

    const dlqChip = Array.from(fixture.nativeElement.querySelectorAll('.chip')).find(
      (el: any) => el.textContent.trim() === 'DLQ',
    ) as HTMLButtonElement;
    dlqChip.click();

    http.expectOne((req: any) => req.url === '/bff/api/v1/messages' && req.params.get('status') === 'DLQ').flush(makeMessages(45));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="monitor-page-info"]').textContent).toContain('Página 1 de 3');
  });
});
