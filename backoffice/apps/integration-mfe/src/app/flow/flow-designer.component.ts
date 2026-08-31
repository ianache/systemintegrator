import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, HostListener, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Flow, FlowGraph, FlowGraphEdge, FlowGraphNode, FlowNodeCategory, FlowVersion } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

type DesignerState = 'loading' | 'ready' | 'not-found' | 'unavailable';

interface NodeCatalogEntry {
  label: string;
  category: FlowNodeCategory;
  fields: string[];
  /** Fields rendered as a monospace textarea (expressions/scripts) instead of a single-line input. */
  expressionFields?: string[];
  /** Placeholder text shown in the expression textarea, matching the design's sample expressions. */
  expressionPlaceholder?: string;
  /** TARGET nodes have no output port in the design. */
  hasOutput?: boolean;
}

interface PaletteGroup {
  category: FlowNodeCategory;
  items: { type: string; label: string }[];
}

interface EdgePath {
  key: string;
  d: string;
  color: string;
  data: FlowGraphEdge;
}

const NODE_W = 180;
const NODE_H = 62;
const GRID_COL_GAP = 240;
const GRID_ROW_GAP = 140;
const GRID_COLS = 4;

const CATEGORY_COLOR: Record<FlowNodeCategory, string> = {
  SOURCE: 'var(--dir-in)',
  TRANSFORM: 'var(--dir-out)',
  CONTROL: 'var(--warn)',
  SPLIT: 'var(--accent-fg)',
  TARGET: 'var(--text-muted)',
};

const CATEGORY_ORDER: FlowNodeCategory[] = ['SOURCE', 'TRANSFORM', 'CONTROL', 'SPLIT', 'TARGET'];

const NODE_CATALOG: Record<string, NodeCatalogEntry> = {
  JDBC_SOURCE: { label: 'JDBC Source', category: 'SOURCE', fields: ['credentialRef', 'query', 'watermarkColumn', 'cron'] },
  REST_SOURCE_POLL: { label: 'REST Source (polling)', category: 'SOURCE', fields: ['url', 'cron', 'itemsPath'] },
  REST_SOURCE_HOOK: { label: 'REST Source (webhook)', category: 'SOURCE', fields: ['path', 'auth'] },
  KAFKA_SOURCE: { label: 'Kafka Source', category: 'SOURCE', fields: ['topics', 'groupId', 'offsetReset'] },
  TRANSFORM_JSLT: {
    label: 'Transform (JSLT)', category: 'TRANSFORM', fields: ['script'],
    expressionFields: ['script'], expressionPlaceholder: '{\n  "vin": .Vehiculo.NumeroChasis\n}',
  },
  TRANSFORM_VELOCITY: {
    label: 'Transform (Velocity)', category: 'TRANSFORM', fields: ['script'],
    expressionFields: ['script'], expressionPlaceholder: '<Vehiculo>$root.chasis</Vehiculo>',
  },
  TRANSFORM_MUSTACHE: {
    label: 'Transform (Mustache)', category: 'TRANSFORM', fields: ['script'],
    expressionFields: ['script'], expressionPlaceholder: 'Unidad {{placa}} dada de baja.',
  },
  FIELD_MAPPING: { label: 'Field Mapping', category: 'TRANSFORM', fields: ['rules'] },
  ENRICHER: { label: 'Enricher (lookup)', category: 'TRANSFORM', fields: ['catalog', 'key', 'defaultValue'] },
  ROUTER_IF: {
    label: 'Router (if / then)', category: 'CONTROL', fields: ['expression'],
    expressionFields: ['expression'], expressionPlaceholder: "payload.estado == '1'",
  },
  SWITCH_CASE: {
    label: 'Switch (case)', category: 'CONTROL', fields: ['expression'],
    expressionFields: ['expression'], expressionPlaceholder: 'payload.tipo',
  },
  FILTER: {
    label: 'Filter', category: 'CONTROL', fields: ['expression'],
    expressionFields: ['expression'], expressionPlaceholder: 'payload.placa != null',
  },
  SPLITTER: { label: 'Splitter', category: 'SPLIT', fields: ['arrayPath'] },
  AGGREGATOR: {
    label: 'Aggregator / Join', category: 'SPLIT', fields: ['correlationKey', 'timeoutMs', 'expression'],
    expressionFields: ['expression'], expressionPlaceholder: 'buffer.size == expectedCount',
  },
  DELAY: { label: 'Delay / Throttle', category: 'SPLIT', fields: ['waitMs'] },
  SCRIPT: {
    label: 'Script (avanzado)', category: 'SPLIT', fields: ['script'],
    expressionFields: ['script'], expressionPlaceholder: '// script libre',
  },
  KAFKA_TARGET: { label: 'Kafka Target', category: 'TARGET', fields: ['topic', 'key'], hasOutput: false },
  DB_TARGET: { label: 'DB Target (sink)', category: 'TARGET', fields: ['table', 'mode'], hasOutput: false },
  REST_TARGET: { label: 'REST Target', category: 'TARGET', fields: ['method', 'url'], hasOutput: false },
};

interface DragState {
  nodeId: string;
  startClientX: number;
  startClientY: number;
  startX: number;
  startY: number;
}

interface EdgeDragState {
  fromId: string;
  x: number;
  y: number;
}

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
  readonly saveError = signal<string | null>(null);
  readonly publishError = signal<string | null>(null);

  readonly graph = signal<FlowGraph>({ nodes: [], edges: [] });
  readonly hasDraft = signal(false);
  readonly selectedNodeId = signal<string | null>(null);
  readonly selectedEdge = signal<FlowGraphEdge | null>(null);
  readonly edgeDrag = signal<EdgeDragState | null>(null);
  readonly hoveredNodeId = signal<string | null>(null);

  @ViewChild('surface') private surfaceRef?: ElementRef<HTMLDivElement>;

  readonly paletteGroups: PaletteGroup[] = CATEGORY_ORDER.map((category) => ({
    category,
    items: Object.entries(NODE_CATALOG)
      .filter(([, entry]) => entry.category === category)
      .map(([type, entry]) => ({ type, label: entry.label })),
  }));

  private dragState: DragState | null = null;
  private nextNodeSeq = 1;

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

  onNameInput(value: string): void {
    this.nameDraft.set(value);
  }

  onTriggerInput(value: string): void {
    this.triggerDraft.set(value);
  }

  saveDraft(): void {
    const current = this.flow();
    if (!current) return;
    this.saveError.set(null);
    const g = this.graph();
    const draftGraph = g.nodes.length === 0 && g.edges.length === 0 ? null : g;
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

  // --- Canvas -------------------------------------------------------------

  categoryOf(type: string): FlowNodeCategory {
    return NODE_CATALOG[type]?.category ?? 'TARGET';
  }

  categoryColor(category: FlowNodeCategory): string {
    return CATEGORY_COLOR[category];
  }

  labelFor(type: string): string {
    return NODE_CATALOG[type]?.label ?? type;
  }

  fieldKeys(node: FlowGraphNode): string[] {
    return NODE_CATALOG[node.type]?.fields ?? Object.keys(node.fields ?? {});
  }

  selectedNode(): FlowGraphNode | null {
    const id = this.selectedNodeId();
    if (!id) return null;
    return this.graph().nodes.find((n) => n.id === id) ?? null;
  }

  selectNode(id: string): void {
    this.selectedNodeId.set(id);
    this.selectedEdge.set(null);
  }

  selectEdge(edge: FlowGraphEdge): void {
    this.selectedEdge.set(edge);
    this.selectedNodeId.set(null);
  }

  clearSelection(): void {
    this.selectedNodeId.set(null);
    this.selectedEdge.set(null);
  }

  removeSelectedEdge(): void {
    const edge = this.selectedEdge();
    if (!edge) return;
    this.graph.update((g) => ({ nodes: g.nodes, edges: g.edges.filter((e) => e !== edge) }));
    this.selectedEdge.set(null);
  }

  hasOutput(type: string): boolean {
    return NODE_CATALOG[type]?.hasOutput ?? true;
  }

  isExpressionField(node: FlowGraphNode, field: string): boolean {
    return (NODE_CATALOG[node.type]?.expressionFields ?? []).includes(field);
  }

  expressionPlaceholder(node: FlowGraphNode): string {
    return NODE_CATALOG[node.type]?.expressionPlaceholder ?? '';
  }

  canvasWidth(): number {
    const nodes = this.graph().nodes;
    const maxX = nodes.reduce((m, n) => Math.max(m, (n.x ?? 0) + NODE_W), 0);
    return Math.max(maxX + 80, 960);
  }

  canvasHeight(): number {
    const nodes = this.graph().nodes;
    const maxY = nodes.reduce((m, n) => Math.max(m, (n.y ?? 0) + NODE_H), 0);
    return Math.max(maxY + 80, 480);
  }

  edgePaths(): EdgePath[] {
    const nodesById = new Map(this.graph().nodes.map((n) => [n.id, n]));
    const paths: EdgePath[] = [];
    this.graph().edges.forEach((edge, index) => {
      const from = nodesById.get(edge.from);
      const to = nodesById.get(edge.to);
      if (!from || !to) return;
      const x1 = (from.x ?? 0) + NODE_W;
      const y1 = (from.y ?? 0) + NODE_H / 2;
      const x2 = to.x ?? 0;
      const y2 = (to.y ?? 0) + NODE_H / 2;
      const midX = (x1 + x2) / 2;
      paths.push({
        key: edge.from + '->' + edge.to + ':' + index,
        d: `M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`,
        color: this.categoryColor(this.categoryOf(from.type)),
        data: edge,
      });
    });
    return paths;
  }

  addNode(type: string): void {
    const catalogEntry = NODE_CATALOG[type];
    if (!catalogEntry) return;
    const id = 'n' + this.nextNodeSeq++;
    const index = this.graph().nodes.length;
    const position = this.gridPosition(index, index + 1);
    const node: FlowGraphNode = {
      id,
      type,
      name: catalogEntry.label,
      x: position.x,
      y: position.y,
      fields: {},
    };
    this.graph.update((g) => ({ nodes: [...g.nodes, node], edges: g.edges }));
    this.selectedNodeId.set(id);
  }

  removeNode(id: string): void {
    this.graph.update((g) => ({
      nodes: g.nodes.filter((n) => n.id !== id),
      edges: g.edges.filter((e) => e.from !== id && e.to !== id),
    }));
    if (this.selectedNodeId() === id) this.selectedNodeId.set(null);
    const edge = this.selectedEdge();
    if (edge && (edge.from === id || edge.to === id)) this.selectedEdge.set(null);
  }

  onNodeNameInput(id: string, value: string): void {
    this.updateNode(id, (n) => ({ ...n, name: value }));
  }

  onFieldInput(id: string, key: string, value: string): void {
    this.updateNode(id, (n) => ({ ...n, fields: { ...(n.fields ?? {}), [key]: value } }));
  }

  onNodeMouseDown(event: MouseEvent, id: string): void {
    const node = this.graph().nodes.find((n) => n.id === id);
    if (!node) return;
    event.preventDefault();
    this.selectNode(id);
    this.dragState = {
      nodeId: id,
      startClientX: event.clientX,
      startClientY: event.clientY,
      startX: node.x ?? 0,
      startY: node.y ?? 0,
    };
  }

  onPortMouseDown(event: MouseEvent, nodeId: string): void {
    event.preventDefault();
    event.stopPropagation();
    const local = this.toLocalPoint(event.clientX, event.clientY);
    this.edgeDrag.set({ fromId: nodeId, x: local.x, y: local.y });
  }

  onNodeMouseEnter(nodeId: string): void {
    if (this.edgeDrag()) this.hoveredNodeId.set(nodeId);
  }

  onNodeMouseLeave(nodeId: string): void {
    if (this.hoveredNodeId() === nodeId) this.hoveredNodeId.set(null);
  }

  tempEdgePath(): string | null {
    const drag = this.edgeDrag();
    if (!drag) return null;
    const from = this.graph().nodes.find((n) => n.id === drag.fromId);
    if (!from) return null;
    const x1 = (from.x ?? 0) + NODE_W;
    const y1 = (from.y ?? 0) + NODE_H / 2;
    const midX = (x1 + drag.x) / 2;
    return `M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${drag.y}, ${drag.x} ${drag.y}`;
  }

  @HostListener('window:mousemove', ['$event'])
  onWindowMouseMove(event: MouseEvent): void {
    if (this.edgeDrag()) {
      const local = this.toLocalPoint(event.clientX, event.clientY);
      this.edgeDrag.set({ ...this.edgeDrag()!, x: local.x, y: local.y });
      return;
    }
    const drag = this.dragState;
    if (!drag) return;
    const nextX = Math.max(0, drag.startX + (event.clientX - drag.startClientX));
    const nextY = Math.max(0, drag.startY + (event.clientY - drag.startClientY));
    this.updateNode(drag.nodeId, (n) => ({ ...n, x: nextX, y: nextY }));
  }

  @HostListener('window:mouseup')
  onWindowMouseUp(): void {
    this.dragState = null;
    const drag = this.edgeDrag();
    const targetId = this.hoveredNodeId();
    this.edgeDrag.set(null);
    this.hoveredNodeId.set(null);
    if (!drag || !targetId || targetId === drag.fromId) return;
    const exists = this.graph().edges.some((e) => e.from === drag.fromId && e.to === targetId);
    if (exists) return;
    this.graph.update((g) => ({ nodes: g.nodes, edges: [...g.edges, { from: drag.fromId, to: targetId }] }));
  }

  private toLocalPoint(clientX: number, clientY: number): { x: number; y: number } {
    const rect = this.surfaceRef?.nativeElement.getBoundingClientRect();
    if (!rect) return { x: clientX, y: clientY };
    return { x: clientX - rect.left, y: clientY - rect.top };
  }

  private updateNode(id: string, updater: (node: FlowGraphNode) => FlowGraphNode): void {
    this.graph.update((g) => ({
      nodes: g.nodes.map((n) => (n.id === id ? updater(n) : n)),
      edges: g.edges,
    }));
  }

  /**
   * `total` sizes the grid to roughly a square (more columns as the node count
   * grows) so a large draft doesn't end up as one absurdly wide row; `index`
   * always lands in a distinct cell, so nodes never overlap regardless of count.
   */
  private gridPosition(index: number, total: number): { x: number; y: number } {
    const cols = Math.max(GRID_COLS, Math.ceil(Math.sqrt(Math.max(total, 1))));
    const col = index % cols;
    const row = Math.floor(index / cols);
    return { x: 40 + col * GRID_COL_GAP, y: 40 + row * GRID_ROW_GAP };
  }

  private parseGraph(raw: unknown): FlowGraph {
    if (!raw || typeof raw !== 'object') return { nodes: [], edges: [] };
    const source = raw as { nodes?: unknown; edges?: unknown };
    const rawNodes = Array.isArray(source.nodes) ? source.nodes : [];
    const nodes: FlowGraphNode[] = rawNodes.map((entry, index) => {
      const n = (entry ?? {}) as Partial<FlowGraphNode>;
      const hasPosition = typeof n.x === 'number' && typeof n.y === 'number';
      const position = hasPosition ? { x: n.x as number, y: n.y as number } : this.gridPosition(index, rawNodes.length);
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

    this.nextNodeSeq = nodes.length + 1;
    return { nodes, edges };
  }

  private applyFlow(flow: Flow): void {
    this.flow.set(flow);
    this.nameDraft.set(flow.name);
    this.triggerDraft.set(flow.triggerSummary ?? '');
    this.hasDraft.set(flow.draftGraph !== null && flow.draftGraph !== undefined);
    this.graph.set(this.parseGraph(flow.draftGraph));
    this.selectedNodeId.set(null);
  }
}
