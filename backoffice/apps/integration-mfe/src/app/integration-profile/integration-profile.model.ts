export type IntegrationProtocol = 'REST' | 'SOAP' | 'JSON_RPC' | 'KAFKA' | 'JDBC';
export type SyncDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL';
export type SourceOfTruth = 'PLATFORM' | 'EXTERNAL' | 'SHARED';
export type IntegrationProfileStatus = 'ACTIVE' | 'PAUSED' | 'DRAFT' | 'ERROR' | 'DEGRADED' | 'INACTIVE';

export interface IntegrationProfileConfiguration {
  protocol: IntegrationProtocol | null;
  connector: string | null;
  adapter: string | null;
  endpoint: string | null;
  credentialRef: string | null;
  mapping: unknown | null;
  transformation: unknown | null;
  syncPolicy: unknown | null;
  retryPolicy: unknown | null;
  rateLimitPolicy: unknown | null;
  extractionConfig: unknown | null;
}

export interface IntegrationProfile {
  id: string;
  tenantId: string;
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  configuration: IntegrationProfileConfiguration | null;
  active: boolean;
  paused: boolean;
  status: IntegrationProfileStatus;
  lastSyncAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface IntegrationProfileConfigurationPayload {
  protocol?: IntegrationProtocol | null;
  connector?: string | null;
  adapter?: string | null;
  endpoint?: string | null;
  credentialRef?: string | null;
  mapping?: unknown | null;
  transformation?: unknown | null;
  syncPolicy?: unknown | null;
  retryPolicy?: unknown | null;
  rateLimitPolicy?: unknown | null;
  extractionConfig?: unknown | null;
}

export interface CreateIntegrationProfilePayload extends IntegrationProfileConfigurationPayload {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
}

export interface UpdateIntegrationProfilePayload extends CreateIntegrationProfilePayload {
  expectedVersion: number;
}

export interface TriggerSyncResult {
  profileId: string;
  status: string;
  triggeredAt: string;
}

export interface MappingDryRunResult {
  output: string | null;
  error: string | null;
}

export interface ExtractionDryRunResult {
  rows: Record<string, unknown>[] | null;
  totalFetched: number | null;
  error: string | null;
}

export interface ApiProblem {
  title?: string;
  status?: number;
  detail?: string;
  errorCode?: string;
  correlationId?: string;
}
