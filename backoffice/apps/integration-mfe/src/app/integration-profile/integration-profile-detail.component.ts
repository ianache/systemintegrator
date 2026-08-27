import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ApiProblem,
  IntegrationProfile,
  IntegrationProtocol,
  SourceOfTruth,
  SyncDirection,
  UpdateIntegrationProfilePayload,
} from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { ToastService } from '../shared/toast.service';

export type DetailTab = 'general' | 'conn' | 'map' | 'pol' | 'sync';
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

const TABS: { id: DetailTab; label: string }[] = [
  { id: 'general', label: 'General' },
  { id: 'conn', label: 'Conectividad' },
  { id: 'map', label: 'Mapping & Transformation' },
  { id: 'pol', label: 'Políticas' },
  { id: 'sync', label: 'Sincronización' },
];

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

  readonly tabs = TABS;
  readonly state = signal<DetailState>('loading');
  readonly profile = signal<IntegrationProfile | null>(null);
  readonly tab = signal<DetailTab>('general');
  readonly editModel = signal<EditModel | null>(null);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  readonly connectivityValid = computed(() => {
    const m = this.editModel();
    if (!m || !m.protocol) return true;
    return m.connector.trim().length > 0 && m.adapter.trim().length > 0;
  });

  private profileId = '';

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((query) => {
      const requested = query.get('tab') as DetailTab | null;
      if (requested && this.tabs.some((t) => t.id === requested)) {
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

  setTab(tab: DetailTab): void {
    this.tab.set(tab);
    this.router.navigate([], { relativeTo: this.route, queryParams: { tab }, queryParamsHandling: 'merge' });
  }

  updateField<K extends keyof EditModel>(key: K, value: EditModel[K]): void {
    this.editModel.update((current) => (current ? { ...current, [key]: value } : current));
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
