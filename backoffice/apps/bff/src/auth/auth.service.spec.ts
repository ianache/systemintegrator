import { ConfigService } from '@nestjs/config';
import * as client from 'openid-client';
import { AuthService } from './auth.service';

const ISSUER = 'https://oauth2.qa.comsatel.com.pe/realms/Apps';
const AUTHORIZATION_ENDPOINT = `${ISSUER}/protocol/openid-connect/auth`;

const ENV: Record<string, string> = {
  KEYCLOAK_APPS_ISSUER_URI: ISSUER,
  BFF_OIDC_CLIENT_ID: 'backoffice-bff',
  BFF_OIDC_CLIENT_SECRET: 'shhh',
  BFF_PUBLIC_URL: 'http://localhost:4000',
};

describe('AuthService.buildAuthorizationUrl', () => {
  let service: AuthService;
  let discovery: jest.SpiedFunction<typeof client.discovery>;

  beforeEach(() => {
    // Stub only the network round-trip: `discovery` resolves a real
    // Configuration built from static metadata, so the PKCE/state code path
    // under test runs against the genuine openid-client implementation.
    discovery = jest.spyOn(client, 'discovery').mockResolvedValue(
      new client.Configuration(
        {
          issuer: ISSUER,
          authorization_endpoint: AUTHORIZATION_ENDPOINT,
        },
        ENV.BFF_OIDC_CLIENT_ID,
        ENV.BFF_OIDC_CLIENT_SECRET,
      ),
    );

    const config = {
      getOrThrow: (key: string) => {
        if (!(key in ENV)) throw new Error(`missing config ${key}`);
        return ENV[key];
      },
    } as unknown as ConfigService;

    service = new AuthService(config);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('builds an authorization URL bound to the returned PKCE verifier via S256', async () => {
    const { url, codeVerifier, state } = await service.buildAuthorizationUrl();
    const params = new URL(url).searchParams;

    expect(params.get('code_challenge_method')).toBe('S256');
    // The challenge must be the S256 hash of the very verifier handed back to
    // the caller -- this binding is what makes the later code exchange safe.
    expect(params.get('code_challenge')).toBe(
      await client.calculatePKCECodeChallenge(codeVerifier),
    );
    expect(params.get('code_challenge')).not.toBe(codeVerifier);
    expect(params.get('state')).toBe(state);
  });

  it('targets the discovered authorization endpoint with the expected request parameters', async () => {
    const { url } = await service.buildAuthorizationUrl();
    const parsed = new URL(url);

    expect(`${parsed.origin}${parsed.pathname}`).toBe(AUTHORIZATION_ENDPOINT);
    expect(parsed.searchParams.get('client_id')).toBe('backoffice-bff');
    expect(parsed.searchParams.get('response_type')).toBe('code');
    expect(parsed.searchParams.get('scope')).toBe('openid profile');
    expect(parsed.searchParams.get('redirect_uri')).toBe('http://localhost:4000/auth/callback');
  });

  it('generates a fresh verifier, challenge and state on every call', async () => {
    const first = await service.buildAuthorizationUrl();
    const second = await service.buildAuthorizationUrl();

    expect(second.codeVerifier).not.toBe(first.codeVerifier);
    expect(second.state).not.toBe(first.state);
    expect(new URL(second.url).searchParams.get('code_challenge')).not.toBe(
      new URL(first.url).searchParams.get('code_challenge'),
    );
  });

  it('discovers the issuer once and reuses the configuration across logins', async () => {
    await service.buildAuthorizationUrl();
    await service.buildAuthorizationUrl();

    expect(discovery).toHaveBeenCalledTimes(1);
    expect(discovery).toHaveBeenCalledWith(
      new URL(ISSUER),
      'backoffice-bff',
      'shhh',
    );
  });

  it('does not cache a failed discovery', async () => {
    discovery.mockRejectedValueOnce(new Error('issuer unreachable'));

    await expect(service.buildAuthorizationUrl()).rejects.toThrow('issuer unreachable');
    await expect(service.buildAuthorizationUrl()).resolves.toBeDefined();
    expect(discovery).toHaveBeenCalledTimes(2);
  });
});
