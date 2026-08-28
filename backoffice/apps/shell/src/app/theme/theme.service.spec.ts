import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('theme-dark');
  });

  it('defaults to light and does not set the dark class', () => {
    const service = TestBed.inject(ThemeService);
    expect(service.dark()).toBe(false);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(false);
  });

  it('toggles to dark, sets the class, and persists the choice', () => {
    const service = TestBed.inject(ThemeService);
    service.toggle();
    expect(service.dark()).toBe(true);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true);
    expect(localStorage.getItem('backoffice.theme')).toBe('dark');
  });

  it('reads a persisted dark preference on construction', () => {
    localStorage.setItem('backoffice.theme', 'dark');
    const service = TestBed.inject(ThemeService);
    expect(service.dark()).toBe(true);
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true);
  });
});
