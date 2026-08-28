import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-credentials-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Credenciales</h1>
          <p>Las credenciales se resuelven en runtime desde Vault; la consola nunca las almacena ni las muestra.</p>
        </div>
      </div>
      <div class="card">
        <app-console-empty-state
          title="Listado de credential refs no disponible todavía"
          description="El backend no expone hoy un endpoint que liste las referencias de credenciales por tenant. Cada perfil sigue mostrando su propio credentialRef en la pestaña Conectividad."
        />
      </div>
    </section>
  `,
})
export class CredentialsPageComponent {}
