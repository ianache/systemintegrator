import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DeadLetterQueueService } from '../dead-letter-queue/dead-letter-queue.service';
import { DlqReplaySummary } from '../dead-letter-queue/dead-letter-queue.model';
import { MessageMonitorService } from '../message-monitor/message-monitor.service';
import { MessageDetail, MessageDirection, MessageStatusFilter, MessageSummary } from '../message-monitor/message-monitor.model';
import { IntegrationProfileService } from '../integration-profile/integration-profile.service';
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
  private readonly profileService = inject(IntegrationProfileService);
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

  readonly domainFilter = signal('');
  readonly domainOptions = signal<string[]>([]);
  readonly dateFrom = signal('');
  readonly dateTo = signal('');

  readonly messageCount = computed(() => `${this.messages().length} mensajes · ventana reciente`);

  ngOnInit(): void {
    this.profileService.list(true).subscribe({
      next: (profiles) => {
        const domains = Array.from(new Set(profiles.map((p) => p.businessDomain))).sort();
        this.domainOptions.set(domains);
      },
      error: () => this.domainOptions.set([]),
    });
    this.load();
  }

  setFilter(value: MessageStatusFilter): void {
    if (this.filter() === value) return;
    this.filter.set(value);
    this.load();
  }

  setDomainFilter(value: string): void {
    this.domainFilter.set(value);
    this.load();
  }

  setDateFrom(value: string): void {
    this.dateFrom.set(value);
    this.load();
  }

  setDateTo(value: string): void {
    this.dateTo.set(value);
    this.load();
  }

  clearDateFilters(): void {
    this.dateFrom.set('');
    this.dateTo.set('');
    this.load();
  }

  load(): void {
    this.state.set('loading');
    const from = this.dateFrom() ? new Date(this.dateFrom() + 'T00:00:00.000Z').toISOString() : undefined;
    const to = this.dateTo() ? new Date(this.dateTo() + 'T23:59:59.999Z').toISOString() : undefined;
    this.messageService.list(this.filter(), this.domainFilter() || undefined, from, to).subscribe({
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
