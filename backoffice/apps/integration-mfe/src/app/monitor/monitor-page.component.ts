import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DeadLetterQueueService } from '../dead-letter-queue/dead-letter-queue.service';
import { DlqReplaySummary } from '../dead-letter-queue/dead-letter-queue.model';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';
import { ToastService } from '../shared/toast.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-monitor-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  templateUrl: './monitor-page.component.html',
  styleUrl: './monitor-page.component.css',
})
export class MonitorPageComponent {
  private readonly dlqService = inject(DeadLetterQueueService);
  private readonly toast = inject(ToastService);

  readonly replaying = signal(false);
  readonly summary = signal<DlqReplaySummary | null>(null);
  readonly errorMessage = signal<string | null>(null);

  replay(): void {
    this.replaying.set(true);
    this.errorMessage.set(null);
    this.dlqService.replay().subscribe({
      next: (result) => {
        this.replaying.set(false);
        this.summary.set(result);
        this.toast.show('Reproceso de DLQ completado: ' + result.success + '/' + result.total + ' exitosos');
      },
      error: () => {
        this.replaying.set(false);
        this.errorMessage.set('No se pudo ejecutar el reproceso de DLQ.');
      },
    });
  }
}
