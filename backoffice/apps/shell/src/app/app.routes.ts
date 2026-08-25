import { Routes } from '@angular/router';
import { WelcomeComponent } from './app';
// Task 4 generated this library with the workspace import alias `shell-contracts`
// (see `paths` in tsconfig.base.json); the `@backoffice/shell-contracts` scope used
// in the plan text does not exist in this workspace.
import { buildMicroUiRoute } from 'shell-contracts';

export const appRoutes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    component: WelcomeComponent,
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
    redirectTo: '',
  },
];
