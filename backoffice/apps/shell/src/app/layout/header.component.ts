import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { SessionService } from '../session/session.service';
import { ThemeService } from '../theme/theme.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  selector: 'app-header',
  styleUrl: './header.component.css',
  templateUrl: './header.component.html',
})
export class HeaderComponent {
  protected readonly sessionService = inject(SessionService);
  protected readonly theme = inject(ThemeService);

  protected expiryLabel(expiresAt: number | undefined): string | null {
    if (!expiresAt) return null;
    return new Date(expiresAt * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
