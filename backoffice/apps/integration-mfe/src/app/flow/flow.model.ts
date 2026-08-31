export type FlowStatus = 'DRAFT' | 'PUBLISHED' | 'OBSOLETE';
export type FlowVersionState = 'ACTIVE' | 'PUBLISHED' | 'ROLLED_BACK';

export interface Flow {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  draftGraph: unknown | null;
  triggerSummary: string | null;
  activeVersionNumber: number | null;
  status: FlowStatus;
  nodeCount: number;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface FlowVersion {
  id: string;
  flowId: string;
  versionNumber: number;
  graph: unknown;
  state: FlowVersionState;
  publishedBy: string;
  publishedAt: string;
}

export interface CreateFlowPayload {
  code: string;
  name: string;
}

export interface UpdateFlowDraftPayload {
  name: string;
  triggerSummary: string | null;
  draftGraph: unknown | null;
  expectedVersion: number;
}

export type FlowNodeCategory = 'SOURCE' | 'TRANSFORM' | 'CONTROL' | 'SPLIT' | 'TARGET';

export interface FlowGraphNode {
  id: string;
  type: string;
  name: string;
  /** Canvas layout — optional so drafts saved before the visual designer still load. */
  x?: number;
  y?: number;
  fields?: Record<string, string>;
}

export interface FlowGraphEdge {
  from: string;
  to: string;
  fromPort?: number;
  label?: string;
}

export interface FlowGraph {
  nodes: FlowGraphNode[];
  edges: FlowGraphEdge[];
}

export type FlowExecutionStatus = 'SUCCESS' | 'FAILURE';

export interface FlowMetricsSummary {
  publishedFlowCount: number;
  executions24h: number;
  errorRatePct: number;
  p95DurationMs: number | null;
}

export interface ReportFlowExecutionPayload {
  flowVersionNumber: number;
  status: FlowExecutionStatus;
  startedAt: string;
  finishedAt: string;
  errorMessage?: string | null;
}
