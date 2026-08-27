import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-monitor-page',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <div>
          <h1>Monitor de mensajes</h1>
          <p>Outbox e Inbox del tenant.</p>
        </div>
      </div>
    </section>
  `,
})
export class MonitorPageComponent {}
