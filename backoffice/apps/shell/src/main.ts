import { initFederation } from '@angular-architects/native-federation';

// Native Federation's shared @angular/core chunk can execute before Angular's
// own initNgDevMode() runs (angular-architects/module-federation-plugin#458),
// throwing "ngDevMode is not defined" on first component/service construction
// in a lazily-loaded remote. Defining it upfront removes that race entirely.
(globalThis as { ngDevMode?: unknown }).ngDevMode ??= true;

initFederation('federation.manifest.json')
  .catch(err => console.error(err))
  .then(_ => import('./bootstrap'))
  .catch(err => console.error(err));
