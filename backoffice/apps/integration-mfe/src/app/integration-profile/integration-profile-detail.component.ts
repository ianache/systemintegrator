import { ChangeDetectionStrategy, Component, InjectionToken, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ApiProblem,
  ExtractionDryRunResult,
  IntegrationProfile,
  IntegrationProtocol,
  MappingDryRunResult,
  SourceOfTruth,
  SyncDirection,
  TriggerSyncResult,
  UpdateIntegrationProfilePayload,
} from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { ToastService } from '../shared/toast.service';

export const CONFIRM = new InjectionToken<(message: string) => boolean>('CONFIRM', {
  factory: () => (message: string) => window.confirm(message),
});

export type DetailTab = 'general' | 'conn' | 'extract' | 'map' | 'pol' | 'sync';
type DetailState = 'loading' | 'ready' | 'not-found' | 'unavailable';

interface EditModel {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  protocol: IntegrationProtocol | '';
  connector: string;
  adapter: string;
  endpoint: string;
  credentialRef: string;
  // Raw JSON text for the six opaque config blobs. Task 16 adds textareas for
  // five of these; extractionConfig never gets a dedicated editor (the design
  // mockup didn't expose one either), but every field here must still be sent
  // back unedited on save — PUT replaces the whole configuration in one call,
  // so any field missing from the payload is wiped, not left alone.
  mappingJson: string;
  transformationJson: string;
  syncPolicyJson: string;
  retryPolicyJson: string;
  rateLimitPolicyJson: string;
  extractionConfigJson: string;
}

export type MappingEngine = 'FIELD_MAPPING' | 'JSLT' | 'PASSTHROUGH';

export interface MappingFieldRow {
  target: string;
  sourcePath: string;
  transform: string;
  type: string;
  defaultValue: string;
  required: boolean;
}

export interface MappingConfig {
  engine: MappingEngine;
  fields: MappingFieldRow[];
  script: string;
}

type BackoffStrategy = 'EXPONENTIAL' | 'FIXED' | 'LINEAR';

interface SyncPolicyConfig {
  mode: string;
  trigger: string;
  batchSize: number;
}

interface RetryPolicyConfig {
  maxAttempts: number;
  backoff: BackoffStrategy;
  initialIntervalMs: number;
}

interface RateLimitPolicyConfig {
  requestsPerSecond: number;
  burst: number;
}

interface ExtractionConfigForm {
  query: string;
  watermarkParam: string;
  watermarkColumn: string;
  keyColumn: string;
  fetchSize: number;
  batchMode: boolean;
  batchSize: number;
}

function parsePolicy<T extends object>(json: string, defaults: T): T {
  try {
    const parsed = JSON.parse(json);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? { ...defaults, ...parsed }
      : defaults;
  } catch {
    return defaults;
  }
}

function parseMappingConfig(json: string): MappingConfig {
  let parsed: Record<string, unknown> = {};
  const trimmed = json.trim();
  if (trimmed) {
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      parsed = {};
    }
  }
  const rawFields = Array.isArray(parsed['fields']) ? (parsed['fields'] as Record<string, unknown>[]) : [];
  const fields: MappingFieldRow[] = rawFields.map((f) => ({
    target: typeof f['target'] === 'string' ? f['target'] : '',
    sourcePath: typeof f['sourcePath'] === 'string' ? f['sourcePath'] : '',
    transform: typeof f['transform'] === 'string' ? f['transform'] : '',
    type: typeof f['type'] === 'string' ? f['type'] : 'STRING',
    defaultValue: typeof f['defaultValue'] === 'string' ? f['defaultValue'] : '',
    required: f['required'] === true,
  }));
  const script = typeof parsed['script'] === 'string' ? (parsed['script'] as string) : '';

  let engine: MappingEngine;
  if (parsed['engine'] === 'FIELD_MAPPING' || parsed['engine'] === 'JSLT' || parsed['engine'] === 'PASSTHROUGH') {
    engine = parsed['engine'];
  } else if (fields.length > 0) {
    engine = 'FIELD_MAPPING';
  } else if (script) {
    engine = 'JSLT';
  } else {
    engine = 'PASSTHROUGH';
  }

  return { engine, fields, script };
}

function serializeMappingConfig(config: MappingConfig): string {
  if (config.engine === 'FIELD_MAPPING') {
    return JSON.stringify({ engine: 'FIELD_MAPPING', fields: config.fields }, null, 2);
  }
  if (config.engine === 'JSLT') {
    return JSON.stringify({ engine: 'JSLT', script: config.script }, null, 2);
  }
  return JSON.stringify({ engine: 'PASSTHROUGH' }, null, 2);
}

const TABS: { id: DetailTab; label: string; jdbcOnly?: boolean }[] = [
  { id: 'general', label: 'General' },
  { id: 'conn', label: 'Conectividad' },
  { id: 'extract', label: 'Extracción SQL', jdbcOnly: true },
  { id: 'map', label: 'Mapping & Transformation' },
  { id: 'pol', label: 'Políticas' },
  { id: 'sync', label: 'Sincronización' },
];

export type PayloadViewMode = 'JSON' | 'TREE';

export interface SourceTreeNode {
  path: string;
  key: string;
  indent: number;
  isBranch: boolean;
  typeLabel: string;
  valuePreview: string;
  expanded: boolean;
}

const IDENTIFIER_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

function jsonPathForKey(parentPath: string, key: string): string {
  return IDENTIFIER_RE.test(key) ? parentPath + '.' + key : parentPath + "['" + key.replace(/'/g, "\\'") + "']";
}

function formatLeafValue(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  return typeof value === 'string' ? '"' + value + '"' : String(value);
}

function buildSourceTree(root: unknown, collapsedPaths: ReadonlySet<string>): SourceTreeNode[] {
  const nodes: SourceTreeNode[] = [];

  function walk(value: unknown, path: string, key: string, indent: number): void {
    const isArray = Array.isArray(value);
    const isObject = value !== null && typeof value === 'object' && !isArray;
    const isBranch = isArray || isObject;
    const expanded = !collapsedPaths.has(path);
    nodes.push({
      path,
      key,
      indent,
      isBranch,
      typeLabel: isArray ? 'array[' + (value as unknown[]).length + ']' : isObject ? 'object' : typeof value,
      valuePreview: isBranch ? '' : formatLeafValue(value),
      expanded,
    });
    if (!isBranch || !expanded) return;
    if (isArray) {
      (value as unknown[]).forEach((item, i) => walk(item, path + '[' + i + ']', String(i), indent + 1));
    } else {
      for (const childKey of Object.keys(value as Record<string, unknown>)) {
        walk((value as Record<string, unknown>)[childKey], jsonPathForKey(path, childKey), childKey, indent + 1);
      }
    }
  }

  if (root !== null && typeof root === 'object') {
    if (Array.isArray(root)) {
      root.forEach((item, i) => walk(item, '$[' + i + ']', String(i), 0));
    } else {
      for (const key of Object.keys(root as Record<string, unknown>)) {
        walk((root as Record<string, unknown>)[key], jsonPathForKey('$', key), key, 0);
      }
    }
  } else {
    walk(root, '$', '$', 0);
  }

  return nodes;
}

function stringifyOrEmpty(value: unknown): string {
  return value === null || value === undefined ? '' : JSON.stringify(value, null, 2);
}

function parseJsonFieldOrNull(raw: string): unknown | null {
  const trimmed = raw.trim();
  return trimmed ? JSON.parse(trimmed) : null;
}

function toEditModel(profile: IntegrationProfile): EditModel {
  const config = profile.configuration;
  return {
    businessDomain: profile.businessDomain,
    externalSource: profile.externalSource,
    syncDirection: profile.syncDirection,
    sourceOfTruth: profile.sourceOfTruth,
    protocol: config?.protocol ?? '',
    connector: config?.connector ?? '',
    adapter: config?.adapter ?? '',
    endpoint: config?.endpoint ?? '',
    credentialRef: config?.credentialRef ?? '',
    mappingJson: stringifyOrEmpty(config?.mapping),
    transformationJson: stringifyOrEmpty(config?.transformation),
    syncPolicyJson: stringifyOrEmpty(config?.syncPolicy),
    retryPolicyJson: stringifyOrEmpty(config?.retryPolicy),
    rateLimitPolicyJson: stringifyOrEmpty(config?.rateLimitPolicy),
    extractionConfigJson: stringifyOrEmpty(config?.extractionConfig),
  };
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-detail',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './integration-profile-detail.component.html',
  styleUrl: './integration-profile-detail.component.css',
})
export class IntegrationProfileDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly profileService = inject(IntegrationProfileService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(CONFIRM);

  private readonly allTabs = TABS;
  readonly tabs = computed(() => this.allTabs.filter((t) => !t.jdbcOnly || this.editModel()?.protocol === 'JDBC'));
  readonly state = signal<DetailState>('loading');
  readonly profile = signal<IntegrationProfile | null>(null);
  readonly tab = signal<DetailTab>('general');
  readonly editModel = signal<EditModel | null>(null);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly syncing = signal(false);
  readonly syncLog = signal<TriggerSyncResult[]>([]);

  readonly samplePayload = signal('{\n  \n}');
  readonly dryRunResult = signal<MappingDryRunResult | null>(null);
  readonly dryRunPending = signal(false);

  private readonly payloadViewModeRequested = signal<PayloadViewMode>('JSON');
  private readonly treeCollapsedPaths = signal<ReadonlySet<string>>(new Set());
  readonly payloadViewMode = computed<PayloadViewMode>(() =>
    this.payloadViewModeRequested() === 'TREE' && !this.isJsonValid(this.samplePayload())
      ? 'JSON'
      : this.payloadViewModeRequested(),
  );
  readonly sourceTreeNodes = computed<SourceTreeNode[]>(() => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(this.samplePayload());
    } catch {
      return [];
    }
    return buildSourceTree(parsed, this.treeCollapsedPaths());
  });

  readonly extractionDryRunResult = signal<ExtractionDryRunResult | null>(null);
  readonly extractionDryRunPending = signal(false);

  readonly mappingConfig = computed<MappingConfig>(() => parseMappingConfig(this.editModel()?.transformationJson ?? ''));
  readonly syncPolicy = computed<SyncPolicyConfig>(() => parsePolicy(this.editModel()?.syncPolicyJson ?? '', {
    mode: 'EVENT_DRIVEN',
    trigger: 'vehicle.upserted.v1',
    batchSize: 200,
  }));
  readonly retryPolicy = computed<RetryPolicyConfig>(() => {
    const policy = parsePolicy<Partial<RetryPolicyConfig>>(this.editModel()?.retryPolicyJson ?? '', {
      maxAttempts: 5,
      backoff: 'EXPONENTIAL',
      initialIntervalMs: 2000,
    });
    return {
      maxAttempts: Number.isFinite(Number(policy.maxAttempts)) ? Math.max(1, Number(policy.maxAttempts)) : 5,
      backoff: policy.backoff === 'FIXED' || policy.backoff === 'LINEAR' ? policy.backoff : 'EXPONENTIAL',
      initialIntervalMs: Number.isFinite(Number(policy.initialIntervalMs)) ? Math.max(0, Number(policy.initialIntervalMs)) : 2000,
    };
  });
  readonly rateLimitPolicy = computed<RateLimitPolicyConfig>(() => {
    const policy = parsePolicy<Partial<RateLimitPolicyConfig>>(this.editModel()?.rateLimitPolicyJson ?? '', {
      requestsPerSecond: 25,
      burst: 50,
    });
    return {
      requestsPerSecond: Number.isFinite(Number(policy.requestsPerSecond)) ? Math.max(0, Number(policy.requestsPerSecond)) : 25,
      burst: Number.isFinite(Number(policy.burst)) ? Math.max(0, Number(policy.burst)) : 50,
    };
  });

  readonly extractionConfig = computed<ExtractionConfigForm>(() => {
    const policy = parsePolicy<Partial<ExtractionConfigForm>>(this.editModel()?.extractionConfigJson ?? '', {
      query: '',
      watermarkParam: 'lastSyncWithBuffer',
      watermarkColumn: '',
      keyColumn: '',
      fetchSize: 500,
      batchMode: false,
      batchSize: 500,
    });
    return {
      query: typeof policy.query === 'string' ? policy.query : '',
      watermarkParam: typeof policy.watermarkParam === 'string' ? policy.watermarkParam : 'lastSyncWithBuffer',
      watermarkColumn: typeof policy.watermarkColumn === 'string' ? policy.watermarkColumn : '',
      keyColumn: typeof policy.keyColumn === 'string' ? policy.keyColumn : '',
      fetchSize: Number.isFinite(Number(policy.fetchSize)) ? Math.max(1, Number(policy.fetchSize)) : 500,
      batchMode: policy.batchMode === true,
      batchSize: Number.isFinite(Number(policy.batchSize)) ? Math.max(1, Number(policy.batchSize)) : 500,
    };
  });

  readonly connectivityValid = computed(() => {
    const m = this.editModel();
    if (!m || !m.protocol) return true;
    return m.connector.trim().length > 0 && m.adapter.trim().length > 0;
  });

  readonly retrySequence = computed(() => {
    const parsed = this.retryPolicy();
    if (parsed.maxAttempts < 2) return [];
    const sequence: string[] = [];
    let interval = parsed.initialIntervalMs;
    for (let i = 0; i < parsed.maxAttempts - 1; i++) {
      sequence.push(interval + 'ms');
      if (parsed.backoff === 'EXPONENTIAL') interval *= 2;
      if (parsed.backoff === 'LINEAR') interval += parsed.initialIntervalMs;
    }
    return sequence;
  });

  private profileId = '';

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((query) => {
      // Checked against allTabs, not the JDBC-filtered tabs(): editModel() may
      // still be null at this point (profile not loaded yet), which would
      // otherwise make a deep link straight to ?tab=extract silently fail.
      const requested = query.get('tab') as DetailTab | null;
      if (requested && this.allTabs.some((t) => t.id === requested)) {
        this.tab.set(requested);
      }
    });
    this.route.paramMap.subscribe((params) => {
      const id = params.get('profileId');
      if (!id) return;
      this.profileId = id;
      this.load(id);
    });
  }

  reload(): void {
    if (this.profileId) this.load(this.profileId);
  }

  triggerSync(): void {
    const current = this.profile();
    if (!current) return;
    this.syncing.set(true);
    this.profileService.triggerSync(current.id).subscribe({
      next: (result) => {
        this.syncing.set(false);
        this.syncLog.update((log) => [result, ...log]);
        this.toast.show('Sincronización disparada · ' + result.status);
      },
      error: () => {
        this.syncing.set(false);
        this.toast.show('No se pudo disparar la sincronización.');
      },
    });
  }

  deactivateProfile(): void {
    const current = this.profile();
    if (!current) return;
    if (!this.confirm('Esta acción desactiva el perfil y no puede deshacerse desde la consola. ¿Continuar?')) return;
    this.profileService.deactivate(current.id).subscribe({
      next: () => {
        this.profile.set({ ...current, active: false });
        this.toast.show('Perfil desactivado.');
      },
      error: () => this.toast.show('No se pudo desactivar el perfil.'),
    });
  }

  togglePause(): void {
    const current = this.profile();
    if (!current) return;
    const action = current.paused ? this.profileService.resume(current.id) : this.profileService.pause(current.id);
    action.subscribe({
      next: (updated) => {
        this.profile.set(updated);
        this.toast.show(updated.paused ? 'Perfil pausado.' : 'Perfil reanudado.');
      },
      error: () => this.toast.show('No se pudo cambiar el estado del perfil.'),
    });
  }

  isJsonValid(raw: string): boolean {
    if (!raw.trim()) return true;
    try {
      JSON.parse(raw);
      return true;
    } catch {
      return false;
    }
  }

  setTab(tab: DetailTab): void {
    this.tab.set(tab);
    this.router.navigate([], { relativeTo: this.route, queryParams: { tab }, queryParamsHandling: 'merge' });
  }

  updateField<K extends keyof EditModel>(key: K, value: EditModel[K]): void {
    this.editModel.update((current) => (current ? { ...current, [key]: value } : current));
  }

  updatePolicyField(
    policy: 'syncPolicyJson' | 'retryPolicyJson' | 'rateLimitPolicyJson' | 'extractionConfigJson',
    field: string,
    value: string | number | boolean,
  ): void {
    const current = this.editModel();
    if (!current) return;

    let parsed: Record<string, unknown> = {};
    try {
      const candidate = JSON.parse(current[policy]);
      if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) {
        parsed = candidate;
      }
    } catch {
      parsed = {};
    }

    this.updateField(policy, JSON.stringify({ ...parsed, [field]: value }, null, 2));
  }

  setEngine(engine: MappingEngine): void {
    const current = this.mappingConfig();
    this.updateField('transformationJson', serializeMappingConfig({ ...current, engine }));
  }

  updateMappingRow(index: number, patch: Partial<MappingFieldRow>): void {
    const current = this.mappingConfig();
    const fields = current.fields.map((row, i) => (i === index ? { ...row, ...patch } : row));
    this.updateField('transformationJson', serializeMappingConfig({ ...current, fields }));
  }

  addMappingRow(): void {
    const current = this.mappingConfig();
    const fields = [
      ...current.fields,
      { target: 'nuevoCampo', sourcePath: '', transform: '', type: 'STRING', defaultValue: '', required: false },
    ];
    this.updateField('transformationJson', serializeMappingConfig({ ...current, fields }));
  }

  removeMappingRow(index: number): void {
    const current = this.mappingConfig();
    const fields = current.fields.filter((_, i) => i !== index);
    this.updateField('transformationJson', serializeMappingConfig({ ...current, fields }));
  }

  updateScript(script: string): void {
    const current = this.mappingConfig();
    this.updateField('transformationJson', serializeMappingConfig({ ...current, script }));
  }

  updateSamplePayload(value: string): void {
    this.samplePayload.set(value);
  }

  setPayloadViewMode(mode: PayloadViewMode): void {
    if (mode === 'TREE' && !this.isJsonValid(this.samplePayload())) return;
    this.payloadViewModeRequested.set(mode);
  }

  toggleTreeNode(path: string): void {
    this.treeCollapsedPaths.update((collapsed) => {
      const next = new Set(collapsed);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  onTreeNodeDragStart(event: DragEvent, path: string): void {
    event.dataTransfer?.setData('text/plain', path);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy';
  }

  onSourcePathDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onSourcePathDrop(index: number, event: DragEvent): void {
    event.preventDefault();
    const path = event.dataTransfer?.getData('text/plain');
    if (path) this.updateMappingRow(index, { sourcePath: path });
  }

  runDryRun(): void {
    const current = this.profile();
    if (!current || !this.editModel()) return;
    this.dryRunPending.set(true);
    this.dryRunResult.set(null);
    const transformationJson = serializeMappingConfig(this.mappingConfig());
    this.profileService.mappingDryRun(current.id, this.samplePayload(), transformationJson).subscribe({
      next: (result) => {
        this.dryRunPending.set(false);
        this.dryRunResult.set(result);
      },
      error: () => {
        this.dryRunPending.set(false);
        this.dryRunResult.set({ output: null, error: 'No se pudo ejecutar el dry-run.' });
      },
    });
  }

  extractResultColumns(rows: Record<string, unknown>[]): string[] {
    return rows.length ? Object.keys(rows[0]) : [];
  }

  runExtractionDryRun(): void {
    const current = this.profile();
    if (!current) return;
    this.extractionDryRunPending.set(true);
    this.extractionDryRunResult.set(null);
    this.profileService.extractionDryRun(current.id).subscribe({
      next: (result) => {
        this.extractionDryRunPending.set(false);
        this.extractionDryRunResult.set(result);
      },
      error: (error: HttpErrorResponse) => {
        this.extractionDryRunPending.set(false);
        const problem = error.error as ApiProblem | undefined;
        this.extractionDryRunResult.set({ rows: null, totalFetched: null, error: problem?.detail || 'No se pudo ejecutar la consulta.' });
      },
    });
  }

  save(): void {
    const current = this.profile();
    const edits = this.editModel();
    if (!current || !edits) return;

    let mapping: unknown, transformation: unknown, syncPolicy: unknown, retryPolicy: unknown, rateLimitPolicy: unknown, extractionConfig: unknown;
    try {
      mapping = parseJsonFieldOrNull(edits.mappingJson);
      transformation = parseJsonFieldOrNull(edits.transformationJson);
      syncPolicy = parseJsonFieldOrNull(edits.syncPolicyJson);
      retryPolicy = parseJsonFieldOrNull(edits.retryPolicyJson);
      rateLimitPolicy = parseJsonFieldOrNull(edits.rateLimitPolicyJson);
      extractionConfig = parseJsonFieldOrNull(edits.extractionConfigJson);
    } catch {
      this.saveError.set('Uno de los campos de configuración (JSON) no es válido.');
      return;
    }

    const payload: UpdateIntegrationProfilePayload = {
      businessDomain: edits.businessDomain,
      externalSource: edits.externalSource,
      syncDirection: edits.syncDirection,
      sourceOfTruth: edits.sourceOfTruth,
      protocol: edits.protocol || null,
      connector: edits.connector || null,
      adapter: edits.adapter || null,
      endpoint: edits.endpoint || null,
      credentialRef: edits.credentialRef || null,
      mapping,
      transformation,
      syncPolicy,
      retryPolicy,
      rateLimitPolicy,
      extractionConfig,
      expectedVersion: current.version,
    };

    this.saving.set(true);
    this.saveError.set(null);
    this.profileService.update(current.id, payload).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.profile.set(updated);
        this.editModel.set(toEditModel(updated));
        this.toast.show('Cambios guardados · versión ' + updated.version);
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        const problem = error.error as ApiProblem | undefined;
        this.saveError.set(problem?.detail || 'No se pudieron guardar los cambios.');
      },
    });
  }

  private load(id: string): void {
    this.state.set('loading');
    this.profileService.get(id).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.editModel.set(toEditModel(profile));
        this.state.set('ready');
      },
      error: (error: HttpErrorResponse) => {
        this.state.set(error.status === 404 ? 'not-found' : 'unavailable');
      },
    });
  }
}
