import { FlowNodeCategory } from './flow.model';

export interface NodeCatalogEntry {
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

export const NODE_W = 180;
export const NODE_H = 62;

export const CATEGORY_COLOR: Record<FlowNodeCategory, string> = {
  SOURCE: 'var(--dir-in)',
  TRANSFORM: 'var(--dir-out)',
  CONTROL: 'var(--warn)',
  SPLIT: 'var(--accent-fg)',
  TARGET: 'var(--text-muted)',
};

export const CATEGORY_ORDER: FlowNodeCategory[] = ['SOURCE', 'TRANSFORM', 'CONTROL', 'SPLIT', 'TARGET'];

export const NODE_CATALOG: Record<string, NodeCatalogEntry> = {
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

export function categoryOf(type: string): FlowNodeCategory {
  return NODE_CATALOG[type]?.category ?? 'TARGET';
}

export function categoryColor(category: FlowNodeCategory): string {
  return CATEGORY_COLOR[category];
}

export function hasOutput(type: string): boolean {
  return NODE_CATALOG[type]?.hasOutput ?? true;
}
