// Pulls @types/express-session into the program (tsconfig restricts `types`, so
// it is not picked up automatically) and declares the BFF's own session shape.
// The side-effect import also applies the package's global augmentation that
// puts `session` on the Express `Request`.
import 'express-session';

declare module 'express-session' {
  interface SessionData {
    /**
     * In-flight OIDC authorization request. Written by `GET /auth/login` and
     * consumed by the callback handler to complete the PKCE code exchange.
     */
    oidc?: {
      codeVerifier: string;
      state: string;
    };
  }
}
