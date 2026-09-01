import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Flow, FlowMetricsSummary, FlowStatus } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';
import { IntegrationTabsComponent } from '../shared/integration-tabs.component';

type FlowListState = 'loading' | 'ready' | 'empty' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-list',
  standalone: true,
  imports: [ConsoleEmptyStateComponent, IntegrationTabsComponent],
  templateUrl: './flow-list.component.html',
  styleUrl: './flow-list.component.css',
})
export class FlowListComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly router = inject(Router);

  readonly state = signal<FlowListState>('loading');
  readonly flows = signal<Flow[]>([]);
  readonly formOpen = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly createError = signal<string | null>(null);
  readonly metrics = signal<FlowMetricsSummary | null>(null);
  readonly metricsUnavailable = signal(false);

  readonly publishedFlowsNote = computed(() => {
    const total = this.flows().length;
    if (total === 0) return 'con versión activa';
    const draft = this.flows().filter((f) => f.status === 'DRAFT').length;
    const obsolete = this.flows().filter((f) => f.status === 'OBSOLETE').length;
    const parts: string[] = [];
    if (draft > 0) parts.push(`${draft} borrador${draft === 1 ? '' : 'es'}`);
    if (obsolete > 0) parts.push(`${obsolete} obsoleto${obsolete === 1 ? '' : 's'}`);
    return parts.length > 0 ? `de ${total} · ${parts.join(', ')}` : `de ${total}`;
  });

  ngOnInit(): void {
    this.load();
    this.loadMetrics();
  }

  loadMetrics(): void {
    this.metricsUnavailable.set(false);
    this.flowService.getMetricsSummary().subscribe({
      next: (metrics) => this.metrics.set(metrics),
      error: () => this.metricsUnavailable.set(true),
    });
  }

  load(): void {
    this.state.set('loading');
    this.flowService.list().subscribe({
      next: (flows) => {
        this.flows.set(flows);
        this.state.set(flows.length === 0 ? 'empty' : 'ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  open(flow: Flow): void {
    this.router.navigate(['/integration/flows', flow.id]);
  }

  openExecs(flow: Flow, event: Event): void {
    event.stopPropagation();
    this.router.navigate(['/integration/flows', flow.id, 'executions']);
  }

  statusBadgeClass(status: FlowStatus): string {
    return 'badge status-' + status.toLowerCase();
  }

  openForm(): void {
    this.formOpen.set(true);
    this.createError.set(null);
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.newCode.set('');
    this.newName.set('');
  }

  onCodeInput(value: string): void {
    this.newCode.set(value);
  }

  onNameInput(value: string): void {
    this.newName.set(value);
  }

  submitCreate(event: Event): void {
    event.preventDefault();
    this.createError.set(null);
    this.flowService.create({ code: this.newCode(), name: this.newName() }).subscribe({
      next: (flow) => {
        this.closeForm();
        this.router.navigate(['/integration/flows', flow.id]);
      },
      error: () => this.createError.set('No se pudo crear el flujo. Verifica que el código no esté en uso.'),
    });
  }
}
