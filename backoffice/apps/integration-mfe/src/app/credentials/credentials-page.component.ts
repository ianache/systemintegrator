import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CredentialService } from '../credential/credential.service';
import { CredentialSummary } from '../credential/credential.model';

type CredentialsState = 'loading' | 'ready' | 'empty' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-credentials-page',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './credentials-page.component.html',
  styleUrl: './credentials-page.component.css',
})
export class CredentialsPageComponent implements OnInit {
  private readonly credentialService = inject(CredentialService);

  readonly state = signal<CredentialsState>('loading');
  readonly credentials = signal<CredentialSummary[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.credentialService.list().subscribe({
      next: (credentials) => {
        this.credentials.set(credentials);
        this.state.set(credentials.length === 0 ? 'empty' : 'ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  usedByLabel(credential: CredentialSummary): string {
    return credential.usedBy.length > 0 ? credential.usedBy.join(', ') : '—';
  }
}
