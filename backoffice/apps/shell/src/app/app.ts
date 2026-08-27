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
