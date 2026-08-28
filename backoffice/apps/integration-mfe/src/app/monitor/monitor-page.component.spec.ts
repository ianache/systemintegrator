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

  it('shows the empty-state note explaining there is no message browsing API yet', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('no expone una API de lectura');
  });

  it('replays the DLQ and shows the real summary counts', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush({ total: 3, success: 2, failed: 1 });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('total 3');
    expect(text).toContain('éxito 2');
    expect(text).toContain('fallidos 1');
  });

  it('shows an inline error when the replay call fails', () => {
    const fixture = TestBed.createComponent(MonitorPageComponent);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dlq-replay"]') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/inbox/dlq/replay').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No se pudo ejecutar el reproceso');
  });
});
