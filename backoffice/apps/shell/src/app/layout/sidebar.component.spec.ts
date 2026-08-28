import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { SidebarComponent } from './sidebar.component';

describe('SidebarComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('renders the five console navigation links', () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    const hrefs = Array.from(
      fixture.nativeElement.querySelectorAll('a[href]') as NodeListOf<HTMLAnchorElement>,
    ).map((a) => a.getAttribute('href'));

    expect(hrefs).toEqual([
      '/',
      '/integration/monitor',
      '/integration/profiles',
      '/integration/connectors',
      '/integration/credentials',
    ]);
  });

  it('starts pinned open and collapses on toggle', () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component.pinned()).toBe(true);

    const toggle = fixture.nativeElement.querySelector('[data-testid="sidebar-toggle"]') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    expect(component.pinned()).toBe(false);
  });

  it('sets --console-rail-width on the document root to match the pinned (resting) state, not the hover state', () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    expect(document.documentElement.style.getPropertyValue('--console-rail-width')).toBe('232px');

    const toggle = fixture.nativeElement.querySelector('[data-testid="sidebar-toggle"]') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    expect(document.documentElement.style.getPropertyValue('--console-rail-width')).toBe('58px');

    // Hovering while unpinned expands the visible rail (flyout), but must NOT
    // reflow `main` — the reserved grid column only tracks `pinned`.
    fixture.componentInstance.onEnter();
    fixture.detectChanges();
    expect(document.documentElement.style.getPropertyValue('--console-rail-width')).toBe('58px');
  });
});
