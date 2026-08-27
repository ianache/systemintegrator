import { Routes } from '@angular/router';
import { buildMicroUiRoute } from 'shell-contracts';

export const appRoutes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'integration',
  },
  buildMicroUiRoute({
    path: 'integration',
    // Must match the `name` the remote declares in its own federation.config.mjs,
    // and therefore in its remoteEntry.json. Native Federation registers a remote
    // under the name carried by the fetched remoteEntry, so a mismatch here makes
    // `loadRemoteModule` fail with "Remote '...' is not initialized."
    remoteName: 'integration-mfe',
    remoteEntry: 'http://localhost:4202/remoteEntry.json',
    exposedModule: './Routes',
  }),
  {
    path: '**',
    redirectTo: 'integration',
  },
];
