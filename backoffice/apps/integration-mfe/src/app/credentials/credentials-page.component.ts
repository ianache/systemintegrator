import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-credentials-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Credenciales</h1>
          <p>Referencias administradas por Vault.</p>
        </div>
      </div>
    </section>
  `,
})
export class CredentialsPageComponent {}
