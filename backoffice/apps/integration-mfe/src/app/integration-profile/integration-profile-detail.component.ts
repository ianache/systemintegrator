import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-profile-detail',
  standalone: true,
  template: `
    <section class="page">
      <div class="page-header">
        <h1>Profile {{ profileId() }}</h1>
      </div>
    </section>
  `,
})
export class IntegrationProfileDetailComponent {
  private readonly route = inject(ActivatedRoute);
  readonly profileId = toSignal(this.route.paramMap.pipe(map((params) => params.get('profileId') ?? '')), {
    initialValue: '',
  });
}
