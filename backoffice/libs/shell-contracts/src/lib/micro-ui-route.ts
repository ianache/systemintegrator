import { Route } from '@angular/router';

export interface MicroUiRouteManifest {
  path: string;
  remoteName: string;
  remoteEntry: string;
  exposedModule: string;
}

export function buildMicroUiRoute(manifest: MicroUiRouteManifest): Route {
  return {
    path: manifest.path,
    loadChildren: () =>
      import('@angular-architects/native-federation').then(({ loadRemoteModule }) =>
        loadRemoteModule({
          remoteName: manifest.remoteName,
          remoteEntry: manifest.remoteEntry,
          exposedModule: manifest.exposedModule,
        }).then((m) => m.routes),
      ),
  };
}
