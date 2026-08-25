import { HttpClientModule } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './layout/header.component';
import { SidebarComponent } from './layout/sidebar.component';
import { SessionService } from './session/session.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HttpClientModule, HeaderComponent, RouterOutlet, SidebarComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly sessionService = inject(SessionService);

  constructor() {
    this.sessionService.refresh();
  }
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="welcome" aria-labelledby="welcome-title">
      <p class="eyebrow">Administration</p>
      <h1 id="welcome-title">Welcome to Backoffice</h1>
      <p>Use the navigation to view your tenant's integration profiles.</p>
    </section>
  `,
})
export class WelcomeComponent {}
