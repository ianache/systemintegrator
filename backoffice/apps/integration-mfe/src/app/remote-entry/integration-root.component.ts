import { Component } from '@angular/core';
import { IntegrationProfileListComponent } from '../integration-profile/integration-profile-list.component';

@Component({
  selector: 'app-integration-root',
  standalone: true,
  imports: [IntegrationProfileListComponent],
  template: `
    <section data-testid="integration-mfe-loaded">
      <app-integration-profile-list />
    </section>
  `,
})
export class IntegrationRootComponent {}
