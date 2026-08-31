import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Flow, FlowExecutionSummary, FlowGraph, FlowGraphEdge, FlowGraphNode } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';
import { NODE_H, NODE_W, categoryColor, categoryOf, portOffsetY } from './flow-node-catalog';

type DetailState = 'loading' | 'ready' | 'unavailable';
type ViewMode = 'GRAPH' | 'TIMELINE';

interface ReadOnlyEdgePath {
  key: string;
  d: string;
  color: string;
  label: string | null;
  labelX: number;
  labelY: number;
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-execution-detail',
  standalone: true,
  imports: [ConsoleEmptyStateComponent, DatePipe],
  templateUrl: './flow-execution-detail.component.html',
  styleUrl: './flow-execution-detail.component.css',
})
export class FlowExecutionDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly flowService = inject(FlowService);

  readonly state = signal<DetailState>('loading');
  readonly flow = signal<Flow | null>(null);
  readonly execution = signal<FlowExecutionSummary | null>(null);
  readonly flowId = signal('');
  readonly executionId = signal('');
  readonly mode = signal<ViewMode>('GRAPH');

  readonly graph = signal<FlowGraph>({ nodes: [], edges: [] });

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const flowId = params.get('flowId');
      const executionId = params.get('executionId');
      if (!flowId || !executionId) return;
      this.flowId.set(flowId);
      this.executionId.set(executionId);
      this.load(flowId, executionId);
    });
  }

  load(flowId: string, executionId: string): void {
    this.state.set('loading');
    this.flowService.get(flowId).subscribe({
      next: (flow) => {
        this.flow.set(flow);
        this.graph.set(this.readGraph(flow.draftGraph));
        this.flowService.getExecution(flowId, executionId).subscribe({
          next: (execution) => {
            this.execution.set(execution);
            this.state.set('ready');
          },
          error: () => this.state.set('unavailable'),
        });
      },
      error: () => this.state.set('unavailable'),
    });
  }

  retry(): void {
    this.load(this.flowId(), this.executionId());
  }

  back(): void {
    this.router.navigate(['/integration/flows', this.flowId(), 'executions']);
  }

  setMode(mode: ViewMode): void {
    this.mode.set(mode);
  }

  statusBadgeClass(status: FlowExecutionSummary['status']): string {
    return 'badge status-' + status.toLowerCase();
  }

  formatDuration(ms: number): string {
    return ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms';
  }

  categoryOf(type: string): ReturnType<typeof categoryOf> {
    return categoryOf(type);
  }

  categoryColor(category: ReturnType<typeof categoryOf>): string {
    return categoryColor(category);
  }

  canvasWidth(): number {
    const nodes = this.graph().nodes;
    const maxX = nodes.reduce((m, n) => Math.max(m, (n.x ?? 0) + NODE_W), 0);
    return Math.max(maxX + 80, 600);
  }

  canvasHeight(): number {
    const nodes = this.graph().nodes;
    const maxY = nodes.reduce((m, n) => Math.max(m, (n.y ?? 0) + NODE_H), 0);
    return Math.max(maxY + 80, 320);
  }

  edgePaths(): ReadOnlyEdgePath[] {
    const nodesById = new Map(this.graph().nodes.map((n) => [n.id, n]));
    const paths: ReadOnlyEdgePath[] = [];
    this.graph().edges.forEach((edge, index) => {
      const from = nodesById.get(edge.from);
      const to = nodesById.get(edge.to);
      if (!from || !to) return;
      const x1 = (from.x ?? 0) + NODE_W;
      const y1 = (from.y ?? 0) + portOffsetY(from, edge.fromPort);
      const x2 = to.x ?? 0;
      const y2 = (to.y ?? 0) + NODE_H / 2;
      const midX = (x1 + x2) / 2;
      paths.push({
        key: edge.from + '->' + edge.to + ':' + index,
        d: `M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`,
        color: categoryColor(categoryOf(from.type)),
        label: edge.fromPort ?? null,
        labelX: x1 + 8,
        labelY: y1 - 8,
      });
    });
    return paths;
  }

  /**
   * The domain model (FlowExecution) only records the outcome of the whole
   * run — there is no per-node step history yet, so the graph can only be
   * drawn with each node's static category color, not a completed/failed/
   * skipped state per node. Wiring that up needs a FlowExecutionStep concept
   * on the backend (new entity + migration + orchestrator instrumentation),
   * which is out of scope for a frontend-only pass.
   */
  private readGraph(raw: unknown): FlowGraph {
    if (!raw || typeof raw !== 'object') return { nodes: [], edges: [] };
    const source = raw as { nodes?: unknown; edges?: unknown };
    const rawNodes = Array.isArray(source.nodes) ? source.nodes : [];
    const cols = Math.max(4, Math.ceil(Math.sqrt(Math.max(rawNodes.length, 1))));
    const nodes: FlowGraphNode[] = rawNodes.map((entry, index) => {
      const n = (entry ?? {}) as Partial<FlowGraphNode>;
      const hasPosition = typeof n.x === 'number' && typeof n.y === 'number';
      const position = hasPosition
        ? { x: n.x as number, y: n.y as number }
        : { x: 40 + (index % cols) * 240, y: 40 + Math.floor(index / cols) * 140 };
      return {
        id: n.id ?? 'n' + (index + 1),
        type: n.type ?? 'SCRIPT',
        name: n.name ?? n.id ?? 'Node ' + (index + 1),
        x: position.x,
        y: position.y,
        fields: n.fields ?? {},
      };
    });
    const rawEdges = Array.isArray(source.edges) ? source.edges : [];
    const edges: FlowGraphEdge[] = rawEdges
      .map((entry) => entry as Partial<FlowGraphEdge>)
      .filter((e): e is FlowGraphEdge => typeof e.from === 'string' && typeof e.to === 'string')
      .map((e) => ({ from: e.from, to: e.to, fromPort: e.fromPort, label: e.label }));
    return { nodes, edges };
  }
}
