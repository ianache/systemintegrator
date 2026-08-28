import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CredentialsPageComponent } from './credentials-page.component';

describe('CredentialsPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CredentialsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flush(fixture: any, credentials: unknown[]) {
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/credentials').flush(credentials);
    fixture.detectChanges();
  }

  it('renders the heading and the security note', () => {
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    flush(fixture, []);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Credenciales');
    expect(fixture.nativeElement.textContent).toContain('nunca muestra ni almacena el secreto');
  });

  it('shows an empty state when there are no credential references', () => {
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    flush(fixture, []);
    expect(fixture.nativeElement.textContent).toContain('No hay credentialRef registrados todavía');
  });

  it('lists credentials with type, usedBy and state', () => {
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    flush(fixture, [
      { ref: 'secret/cl2/comsatel-unidad-credentials', type: 'BEARER', usedBy: ['units · comsatel-unidad-api'], rotatedAt: '2026-08-20T10:00:00Z', state: 'VIGENTE' },
      { ref: 'secret/cl2/missing-cred', type: null, usedBy: ['orders · erp'], rotatedAt: null, state: 'SIN_VERIFICAR' },
    ]);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('secret/cl2/comsatel-unidad-credentials');
    expect(text).toContain('BEARER');
    expect(text).toContain('units · comsatel-unidad-api');
    expect(text).toContain('Vigente');
    expect(text).toContain('secret/cl2/missing-cred');
    expect(text).toContain('Sin verificar');
    expect(text).toContain('sin verificar'); // rotation column fallback

    const rows = fixture.nativeElement.querySelectorAll('.credentials-row');
    expect(rows.length).toBe(2);
  });

  it('shows an unavailable state with retry when the request fails', () => {
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/credentials').flush('', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('no está disponible temporalmente');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('/bff/api/v1/credentials').flush([]);
  });
});
