import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-console-empty-state',
  standalone: true,
  template: `
    <div class="empty-state">
      <strong>{{ title() }}</strong>
      <span>{{ description() }}</span>
      <ng-content />
    </div>
  `,
})
export class ConsoleEmptyStateComponent {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
