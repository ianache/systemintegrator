import { ChangeDetectionStrategy, Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { JsonPipe } from '@angular/common';
import { ApiProblem, CreateIntegrationProfilePayload, IntegrationProfile, IntegrationProtocol, SourceOfTruth, SyncDirection } from './integration-profile.model';
import { IntegrationProfileService } from './integration-profile.service';
import { ToastService } from '../shared/toast.service';

interface WizardModel {
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  protocol: IntegrationProtocol | '';
  connector: string;
  adapter: string;
  endpoint: string;
  credentialRef: string;
}

const STEP_LABELS = ['Dominio y fuente', 'Dirección y source of truth', 'Conectividad', 'Revisión'];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-wizard',
  standalone: true,
  imports: [JsonPipe],
  templateUrl: './integration-profile-wizard.component.html',
  styleUrl: './integration-profile-wizard.component.css',
})
export class IntegrationProfileWizardComponent {
  private readonly profileService = inject(IntegrationProfileService);
  private readonly toast = inject(ToastService);

  @Output() closed = new EventEmitter<void>();
  @Output() created = new EventEmitter<IntegrationProfile>();

  readonly stepLabels = STEP_LABELS;
  readonly step = signal(0);
  readonly expert = signal(false);
  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly model = signal<WizardModel>({
    businessDomain: '',
    externalSource: '',
    syncDirection: 'INBOUND',
    sourceOfTruth: 'EXTERNAL',
    protocol: '',
    connector: '',
    adapter: '',
    endpoint: '',
    credentialRef: '',
  });

  readonly expertJson = signal('');

  updateField<K extends keyof WizardModel>(key: K, value: WizardModel[K]): void {
    this.model.update((current) => ({ ...current, [key]: value }));
  }

  toggleExpert(): void {
    if (!this.expert()) {
      this.expertJson.set(JSON.stringify(this.buildPayload(), null, 2));
    }
    this.expert.update((value) => !value);
  }

  next(): void {
    this.errorMessage.set(null);
    if (this.expert()) {
      this.submit();
      return;
    }
    if (this.step() === 2 && !this.connectivityValid()) {
      this.errorMessage.set('Si defines protocol, connector y adapter son obligatorios.');
      return;
    }
    if (this.step() === this.stepLabels.length - 1) {
      this.submit();
      return;
    }
    this.step.update((s) => s + 1);
  }

  back(): void {
    this.errorMessage.set(null);
    this.step.update((s) => Math.max(0, s - 1));
  }

  close(): void {
    this.closed.emit();
  }

  private connectivityValid(): boolean {
    const m = this.model();
    if (!m.protocol) return true;
    return m.connector.trim().length > 0 && m.adapter.trim().length > 0;
  }

  private buildPayload(): CreateIntegrationProfilePayload {
    const m = this.model();
    return {
      businessDomain: m.businessDomain,
      externalSource: m.externalSource,
      syncDirection: m.syncDirection,
      sourceOfTruth: m.sourceOfTruth,
      protocol: m.protocol || null,
      connector: m.connector || null,
      adapter: m.adapter || null,
      endpoint: m.endpoint || null,
      credentialRef: m.credentialRef || null,
    };
  }

  private submit(): void {
    let payload: CreateIntegrationProfilePayload;
    if (this.expert()) {
      try {
        payload = JSON.parse(this.expertJson());
      } catch {
        this.errorMessage.set('El JSON no es válido.');
        return;
      }
    } else {
      payload = this.buildPayload();
    }

    this.submitting.set(true);
    this.profileService.create(payload).subscribe({
      next: (profile) => {
        this.submitting.set(false);
        this.toast.show('Perfil creado en estado Borrador.');
        this.created.emit(profile);
      },
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        const problem = error.error as ApiProblem | undefined;
        this.errorMessage.set(problem?.detail || 'No se pudo crear el perfil.');
      },
    });
  }
}
