import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-connectors-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Conectores y adapters</h1>
          <p>Catálogo instalado en el core.</p>
        </div>
      </div>
    </section>
  `,
})
export class ConnectorsPageComponent {}
