import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Flow, FlowVersion } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

type DesignerState = 'loading' | 'ready' | 'not-found' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-designer',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  templateUrl: './flow-designer.component.html',
  styleUrl: './flow-designer.component.css',
})
export class FlowDesignerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly flowService = inject(FlowService);

  readonly state = signal<DesignerState>('loading');
  readonly flow = signal<Flow | null>(null);
  readonly versions = signal<FlowVersion[]>([]);
  readonly flowId = signal('');
  readonly nameDraft = signal('');
  readonly triggerDraft = signal('');
  readonly graphDraft = signal('');
  readonly saveError = signal<string | null>(null);
  readonly publishError = signal<string | null>(null);

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
        this.applyFlow(flow);
        this.flowService.listVersions(id).subscribe({
          next: (versions) => {
            this.versions.set(versions);
            this.state.set('ready');
          },
          error: () => this.state.set('unavailable'),
        });
      },
      error: (error: HttpErrorResponse) => this.state.set(error.status === 404 ? 'not-found' : 'unavailable'),
    });
  }

  retry(): void {
    this.load(this.flowId());
  }

  back(): void {
    this.router.navigate(['/integration/flows']);
  }

  openExecutions(): void {
    this.router.navigate(['/integration/flows', this.flowId(), 'executions']);
  }

  onNameInput(value: string): void {
    this.nameDraft.set(value);
  }

  onTriggerInput(value: string): void {
    this.triggerDraft.set(value);
  }

  onGraphInput(value: string): void {
    this.graphDraft.set(value);
  }

  saveDraft(): void {
    const current = this.flow();
    if (!current) return;
    this.saveError.set(null);
    let draftGraph: unknown = null;
    if (this.graphDraft().trim()) {
      try {
        draftGraph = JSON.parse(this.graphDraft());
      } catch {
        this.saveError.set('El grafo debe ser JSON válido.');
        return;
      }
    }
    this.flowService
      .updateDraft(this.flowId(), {
        name: this.nameDraft(),
        triggerSummary: this.triggerDraft() || null,
        draftGraph,
        expectedVersion: current.version,
      })
      .subscribe({
        next: (flow) => this.applyFlow(flow),
        error: () => this.saveError.set('No se pudo guardar el draft. Puede que otro usuario lo haya modificado.'),
      });
  }

  publish(): void {
    this.publishError.set(null);
    this.flowService.publish(this.flowId()).subscribe({
      next: () => this.load(this.flowId()),
      error: () => this.publishError.set('No se pudo publicar. El draft puede estar vacío.'),
    });
  }

  rollback(versionNumber: number): void {
    this.flowService.rollback(this.flowId(), versionNumber).subscribe({
      next: () => this.load(this.flowId()),
    });
  }

  private applyFlow(flow: Flow): void {
    this.flow.set(flow);
    this.nameDraft.set(flow.name);
    this.triggerDraft.set(flow.triggerSummary ?? '');
    this.graphDraft.set(flow.draftGraph ? JSON.stringify(flow.draftGraph, null, 2) : '');
  }
}
