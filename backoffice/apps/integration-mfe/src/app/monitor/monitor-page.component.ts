import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DeadLetterQueueService } from '../dead-letter-queue/dead-letter-queue.service';
import { DlqReplaySummary } from '../dead-letter-queue/dead-letter-queue.model';
import { MessageMonitorService } from '../message-monitor/message-monitor.service';
import { MessageDetail, MessageDirection, MessageStatusFilter, MessageSummary } from '../message-monitor/message-monitor.model';
import { ToastService } from '../shared/toast.service';

type MessageListState = 'loading' | 'ready' | 'empty' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-monitor-page',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './monitor-page.component.html',
  styleUrl: './monitor-page.component.css',
})
export class MonitorPageComponent implements OnInit {
  private readonly dlqService = inject(DeadLetterQueueService);
  private readonly messageService = inject(MessageMonitorService);
  private readonly toast = inject(ToastService);

  readonly replaying = signal(false);
  readonly summary = signal<DlqReplaySummary | null>(null);
  readonly errorMessage = signal<string | null>(null);

  readonly filter = signal<MessageStatusFilter>('ALL');
  readonly state = signal<MessageListState>('loading');
  readonly messages = signal<MessageSummary[]>([]);

  readonly selected = signal<MessageDetail | null>(null);
  readonly actionPending = signal(false);

  readonly filters: { value: MessageStatusFilter; label: string }[] = [
    { value: 'ALL', label: 'Todos' },
    { value: 'PENDING', label: 'Pendientes' },
    { value: 'ERROR', label: 'Con error' },
    { value: 'DLQ', label: 'DLQ' },
  ];

  readonly messageCount = computed(() => `${this.messages().length} mensajes · ventana reciente`);

  ngOnInit(): void {
    this.load();
  }

  setFilter(value: MessageStatusFilter): void {
    if (this.filter() === value) return;
    this.filter.set(value);
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.messageService.list(this.filter()).subscribe({
      next: (messages) => {
        this.messages.set(messages);
        this.state.set(messages.length === 0 ? 'empty' : 'ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  open(message: MessageSummary): void {
    this.messageService.get(message.direction, message.id).subscribe({
      next: (detail) => this.selected.set(detail),
      error: () => this.toast.show('No se pudo cargar el detalle del mensaje.'),
    });
  }

  close(): void {
    this.selected.set(null);
  }

  retry(): void {
    const current = this.selected();
    if (!current) return;
    this.runAction(current.direction, current.id, (direction, id) => this.messageService.retry(direction, id), 'Mensaje reencolado.');
  }

  moveToDlq(): void {
    const current = this.selected();
    if (!current) return;
    this.runAction(current.direction, current.id, (direction, id) => this.messageService.moveToDlq(direction, id), 'Mensaje movido a DLQ.');
  }

  private runAction(
    direction: MessageDirection,
    id: string,
    action: (direction: MessageDirection, id: string) => ReturnType<MessageMonitorService['retry']>,
    successMessage: string,
  ): void {
    this.actionPending.set(true);
    action(direction, id).subscribe({
      next: () => {
        this.actionPending.set(false);
        this.selected.set(null);
        this.toast.show(successMessage);
        this.load();
      },
      error: () => {
        this.actionPending.set(false);
        this.toast.show('La acción no se pudo completar.');
      },
    });
  }

  replay(): void {
    this.replaying.set(true);
    this.errorMessage.set(null);
    this.dlqService.replay().subscribe({
      next: (result) => {
        this.replaying.set(false);
        this.summary.set(result);
        this.toast.show('Reproceso de DLQ completado: ' + result.success + '/' + result.total + ' exitosos');
        this.load();
      },
      error: () => {
        this.replaying.set(false);
        this.errorMessage.set('No se pudo ejecutar el reproceso de DLQ.');
      },
    });
  }
}
