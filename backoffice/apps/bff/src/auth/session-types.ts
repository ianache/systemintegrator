import 'express-session';

export interface SessionTokens {
  access_token: string;
  id_token: string;
  refresh_token?: string;
  tenantId: string;
  expiresAt: number;
}

export type SessionProjection =
  | { authenticated: false }
  | { authenticated: true; tenantId: string; expiresAt: number };

export function projectSession(tokens?: SessionTokens): SessionProjection {
  if (!tokens) {
    return { authenticated: false };
  }

  return {
    authenticated: true,
    tenantId: tokens.tenantId,
    expiresAt: tokens.expiresAt,
  };
}

declare module 'express-session' {
  interface SessionData {
    tokens?: SessionTokens;
  }
}
