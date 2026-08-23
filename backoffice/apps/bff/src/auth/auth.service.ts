import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as client from 'openid-client';

export interface AuthorizationRequest {
  url: string;
  codeVerifier: string;
  state: string;
}

@Injectable()
export class AuthService {
  // Memoises the in-flight discovery promise rather than the resolved value so
  // that concurrent logins during cold start share a single discovery request.
  private configuration?: Promise<client.Configuration>;

  constructor(private readonly config: ConfigService) {}

  private getConfiguration(): Promise<client.Configuration> {
    if (!this.configuration) {
      this.configuration = client
        .discovery(
          new URL(this.config.getOrThrow('KEYCLOAK_APPS_ISSUER_URI')),
          this.config.getOrThrow('BFF_OIDC_CLIENT_ID'),
          this.config.getOrThrow('BFF_OIDC_CLIENT_SECRET'),
        )
        .catch((error) => {
          // Do not cache a failed discovery: drop it so the next login retries.
          this.configuration = undefined;
          throw error;
        });
    }
    return this.configuration;
  }

  async buildAuthorizationUrl(): Promise<AuthorizationRequest> {
    const configuration = await this.getConfiguration();
    // A fresh verifier and state per authorization request: the verifier binds
    // the eventual code exchange to this browser (PKCE), the state binds the
    // callback to this session (CSRF).
    const codeVerifier = client.randomPKCECodeVerifier();
    const codeChallenge = await client.calculatePKCECodeChallenge(codeVerifier);
    const state = client.randomState();
    const url = client.buildAuthorizationUrl(configuration, {
      redirect_uri: `${this.config.getOrThrow('BFF_PUBLIC_URL')}/auth/callback`,
      scope: 'openid profile',
      code_challenge: codeChallenge,
      code_challenge_method: 'S256',
      state,
    });
    return { url: url.href, codeVerifier, state };
  }
}
