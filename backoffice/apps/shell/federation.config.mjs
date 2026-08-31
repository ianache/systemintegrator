import { withNativeFederation, shareAll } from '@angular-architects/native-federation/config';

export default withNativeFederation({
  name: 'shell',


  shared: {
    ...shareAll(
      { singleton: true, strictVersion: true, requiredVersion: 'auto', build: 'package' },
      {
        overrides: {
          // includeSecondaries is an opt-out of ignoreUnusedDeps, so all of
          // @angular/core is shared to prevent mismatches.
          '@angular/core': { singleton: true, strictVersion: true, requiredVersion: 'auto', build: 'package', includeSecondaries: { keepAll: true } },
        },
      },
    ),
  },

  skip: [
    // `shell-contracts` is a workspace source library (a tsconfig `paths`
    // alias), not a versioned npm package, so it must not be shared as an
    // external. When it is, it is emitted as its own bundle carrying a second
    // copy of the federation runtime; that copy's `initFederation` is never
    // called, so `loadRemoteModule` from `buildMicroUiRoute` never settles and
    // the `/integration` route hangs silently. Skipping it bundles the library
    // into the host, next to the runtime instance `main.ts` initializes.
    'shell-contracts',
    'rxjs/ajax',
    'rxjs/fetch',
    'rxjs/testing',
    'rxjs/webSocket',
    // Add further packages you don't need at runtime
  ],

  // Please read our FAQ about sharing libs:
  // https://shorturl.at/jmzH0

  features: {
    // ignoreUnusedDeps is enabled by default now
    // ignoreUnusedDeps: true,

    // Disabled: was causing shared externals (e.g. rxjs `map`) to resolve
    // against the wrong merged chunk at runtime.
    denseChunking: false
  }
});
