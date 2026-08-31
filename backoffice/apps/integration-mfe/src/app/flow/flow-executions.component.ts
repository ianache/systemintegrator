import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Flow, FlowExecutionSummary } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

type ExecutionsState = 'loading' | 'ready' | 'unavailable';
type ExecutionFilter = 'ALL' | 'FAILURE';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-executions',
  standalone: true,
  imports: [ConsoleEmptyStateComponent, DatePipe],
  templateUrl: './flow-executions.component.html',
  styleUrl: './flow-executions.component.css',
})
export class FlowExecutionsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly flowService = inject(FlowService);

  readonly state = signal<ExecutionsState>('loading');
  readonly flow = signal<Flow | null>(null);
  readonly executions = signal<FlowExecutionSummary[]>([]);
  readonly flowId = signal('');
  readonly filter = signal<ExecutionFilter>('ALL');

  readonly filteredExecutions = computed(() => {
    const list = this.executions();
    return this.filter() === 'FAILURE' ? list.filter((e) => e.status === 'FAILURE') : list;
  });

  readonly kpis = computed(() => {
    const list = this.executions();
    const total = list.length;
    const failures = list.filter((e) => e.status === 'FAILURE').length;
    const durations = list.map((e) => e.durationMs).sort((a, b) => a - b);
    const p95Index = Math.min(durations.length - 1, Math.ceil(durations.length * 0.95) - 1);
    const p95 = durations.length ? durations[p95Index] : null;
    return {
      total,
      errorRatePct: total ? Math.round((failures / total) * 1000) / 10 : 0,
      p95DurationMs: p95,
    };
  });

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('flowId');
      if (!id) return;
      this.flowId.set(id);
      this.load(id);
    });
  }

  load(id: string): void {
    this.state.set('loading');
    this.flowService.get(id).subscribe({
      next: (flow) => {
        this.flow.set(flow);
        this.flowService.listExecutions(id).subscribe({
          next: (executions) => {
            this.executions.set(executions);
            this.state.set('ready');
          },
          error: () => this.state.set('unavailable'),
        });
      },
      error: () => this.state.set('unavailable'),
    });
  }

  retry(): void {
    this.load(this.flowId());
  }

  back(): void {
    this.router.navigate(['/integration/flows', this.flowId()]);
  }

  setFilter(filter: ExecutionFilter): void {
    this.filter.set(filter);
  }

  open(execution: FlowExecutionSummary): void {
    this.router.navigate(['/integration/flows', this.flowId(), 'executions', execution.id]);
  }

  statusBadgeClass(status: FlowExecutionSummary['status']): string {
    return 'badge status-' + status.toLowerCase();
  }

  formatDuration(ms: number): string {
    return ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms';
  }
}
