import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { IntegrationProfileService } from '../integration-profile/integration-profile.service';
import { IntegrationProfile, IntegrationProtocol } from '../integration-profile/integration-profile.model';

type ConnectorsState = 'loading' | 'ready' | 'empty' | 'unavailable';

export interface ConnectorSummary {
  name: string;
  protocol: IntegrationProtocol | null;
  adapters: string[];
  activeProfiles: number;
  totalProfiles: number;
  inUse: boolean;
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-connectors-page',
  standalone: true,
  imports: [],
  templateUrl: './connectors-page.component.html',
  styleUrl: './connectors-page.component.css',
})
export class ConnectorsPageComponent implements OnInit {
  private readonly profileService = inject(IntegrationProfileService);

  readonly state = signal<ConnectorsState>('loading');
  readonly connectors = signal<ConnectorSummary[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.profileService.list(false).subscribe({
      next: (profiles) => {
        const summaries = ConnectorsPageComponent.summarize(profiles);
        this.connectors.set(summaries);
        this.state.set(summaries.length === 0 ? 'empty' : 'ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  private static summarize(profiles: IntegrationProfile[]): ConnectorSummary[] {
    const byConnector = new Map<string, IntegrationProfile[]>();
    for (const profile of profiles) {
      const connector = profile.configuration?.connector;
      if (!connector) continue;
      const group = byConnector.get(connector) ?? [];
      group.push(profile);
      byConnector.set(connector, group);
    }

    return Array.from(byConnector.entries())
      .map(([name, group]) => {
        const adapters = Array.from(
          new Set(group.map((p) => p.configuration?.adapter).filter((a): a is string => !!a)),
        ).sort();
        const activeProfiles = group.filter((p) => p.active).length;
        return {
          name,
          protocol: group[0].configuration?.protocol ?? null,
          adapters,
          activeProfiles,
          totalProfiles: group.length,
          inUse: activeProfiles > 0,
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name));
  }
}
