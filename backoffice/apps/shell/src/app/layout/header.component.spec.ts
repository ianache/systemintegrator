import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HeaderComponent } from './header.component';
import { SessionService } from '../session/session.service';
import { ThemeService } from '../theme/theme.service';

describe('HeaderComponent', () => {
  it('shows the tenant from the session and no tenant switcher', async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(HeaderComponent);
    const session = TestBed.inject(SessionService);
    session.session.set({ authenticated: true, tenantId: 'tenant-abc', expiresAt: 1893456000 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('tenant-abc');
    expect(fixture.nativeElement.querySelector('[data-testid="tenant-switcher"]')).toBeNull();
  });

  it('toggles the theme via ThemeService', async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(HeaderComponent);
    fixture.detectChanges();
    const theme = TestBed.inject(ThemeService);
    const button = fixture.nativeElement.querySelector('[data-testid="theme-toggle"]') as HTMLButtonElement;

    expect(theme.dark()).toBe(false);
    button.click();
    expect(theme.dark()).toBe(true);
  });
});
