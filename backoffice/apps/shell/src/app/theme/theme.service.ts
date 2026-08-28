import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'backoffice.theme';
const DARK_CLASS = 'theme-dark';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly dark = signal(this.readPersisted());

  constructor() {
    this.applyClass(this.dark());
  }

  toggle(): void {
    const next = !this.dark();
    this.dark.set(next);
    this.applyClass(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? 'dark' : 'light');
    } catch {
      // Storage can be unavailable (private browsing); theme still applies for this session.
    }
  }

  private readPersisted(): boolean {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'dark';
    } catch {
      return false;
    }
  }

  private applyClass(dark: boolean): void {
    document.documentElement.classList.toggle(DARK_CLASS, dark);
  }
}
