import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-connectors-page',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Conectores y adapters</h1>
          <p>Catálogo instalado en el core de integración.</p>
        </div>
      </div>
      <div class="card">
        <app-console-empty-state
          title="Catálogo de connectors no disponible todavía"
          description="El backend no expone hoy un endpoint que liste los connectors y adapters instalados. Esta vista se completará cuando exista esa API."
        />
      </div>
    </section>
  `,
})
export class ConnectorsPageComponent {}
