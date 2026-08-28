import { TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('shows a message and clears it after the duration', () => {
    const service = TestBed.inject(ToastService);
    service.show('Perfil creado', 1000);
    expect(service.message()).toBe('Perfil creado');

    vi.advanceTimersByTime(1000);
    expect(service.message()).toBeNull();
  });

  it('replaces an in-flight toast and restarts its timer', () => {
    const service = TestBed.inject(ToastService);
    service.show('First', 1000);
    vi.advanceTimersByTime(500);
    service.show('Second', 1000);

    vi.advanceTimersByTime(500);
    expect(service.message()).toBe('Second');

    vi.advanceTimersByTime(500);
    expect(service.message()).toBeNull();
  });
});
