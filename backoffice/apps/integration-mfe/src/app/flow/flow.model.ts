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
