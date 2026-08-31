import { FlowGraphNode, FlowNodeCategory } from './flow.model';

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
    label: 'Switch (case)', category: 'CONTROL', fields: ['expression', 'cases'],
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

/**
 * Output ports for a node, in render order. Matches the mock's NODE_SPEC.outs:
 * ROUTER_IF is a fixed true/false branch, SWITCH_CASE derives one port per
 * case name the user typed into the node's `cases` field (comma-separated)
 * plus a trailing "default", and every other node with an output gets a
 * single unnamed port ('' — no label rendered, no fromPort set on its edges).
 */
export function outputPortsFor(node: FlowGraphNode): string[] {
  if (node.type === 'ROUTER_IF') return ['true', 'false'];
  if (node.type === 'SWITCH_CASE') {
    const raw = node.fields?.['cases'] ?? '';
    const cases = raw.split(',').map((c) => c.trim()).filter(Boolean);
    return [...cases, 'default'];
  }
  return hasOutput(node.type) ? [''] : [];
}

/** Vertical offset (px) of a named output port within a NODE_H-tall node box. */
export function portOffsetY(node: FlowGraphNode, portKey: string | undefined): number {
  if (!portKey) return NODE_H / 2;
  const ports = outputPortsFor(node);
  const index = ports.indexOf(portKey);
  if (index === -1) return NODE_H / 2;
  return Math.round((NODE_H * (index + 1)) / (ports.length + 1));
}
