import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { appRoutes } from './app.routes';
import { SessionService } from './session/session.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        {
          provide: SessionService,
          useValue: {
            refresh: () => undefined,
            session: () => ({ authenticated: false }),
          },
        },
      ],
    }).compileComponents();
  });

  it('should render the shell layout', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-header')).not.toBeNull();
    expect(compiled.querySelector('app-sidebar')).not.toBeNull();
    expect(compiled.querySelector('main#main-content')).not.toBeNull();
  });

  it('does not apply the dark theme class by default', () => {
    TestBed.createComponent(App);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(false);
  });
});

describe('appRoutes', () => {
  it('redirects the root path to /integration', () => {
    expect(appRoutes[0]).toMatchObject({ path: '', pathMatch: 'full', redirectTo: 'integration' });
  });

  it('redirects unknown paths to /integration', () => {
    const wildcard = appRoutes.find((route) => route.path === '**');
    expect(wildcard?.redirectTo).toBe('integration');
  });
});
