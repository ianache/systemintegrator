import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-dashboard-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Salud de integraciones</h1>
          <p>Perfiles activos del tenant autenticado.</p>
        </div>
      </div>
    </section>
  `,
})
export class DashboardPageComponent {}
