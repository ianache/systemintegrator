import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  show(text: string, durationMs = 2600): void {
    clearTimeout(this.timer);
    this.message.set(text);
    this.timer = setTimeout(() => this.message.set(null), durationMs);
  }
}
