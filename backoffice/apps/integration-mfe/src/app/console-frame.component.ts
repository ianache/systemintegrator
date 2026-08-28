import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastService } from './shared/toast.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-console-frame',
  standalone: true,
  imports: [RouterOutlet],
  template: `
    <router-outlet />
    @if (toast.message(); as message) {
      <div class="toast">
        <span class="toast-dot"></span>
        {{ message }}
      </div>
    }
  `,
  styles: [
    `.toast-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); display: inline-block; }`,
  ],
})
export class ConsoleFrameComponent {
  protected readonly toast = inject(ToastService);
}
