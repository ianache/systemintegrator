import { ConfigService } from '@nestjs/config';
import { readBackofficeConfig } from './backoffice-config';

describe('readBackofficeConfig', () => {
  it('reads every required Backoffice runtime value', () => {
    const config = readBackofficeConfig(
      new ConfigService({
        KEYCLOAK_APPS_ISSUER_URI: 'https://issuer.example/realms/Apps',
        BFF_OIDC_CLIENT_ID: 'backoffice',
        BFF_OIDC_CLIENT_SECRET: 'client-secret',
        BFF_SESSION_SECRET: 'session-secret',
        BFF_PUBLIC_URL: 'http://localhost:4000',
        GATEWAY_URI: 'http://localhost:8081',
        REDIS_URL: 'redis://localhost:6379',
      }),
    );

    expect(config).toEqual({
      KEYCLOAK_APPS_ISSUER_URI: 'https://issuer.example/realms/Apps',
      BFF_OIDC_CLIENT_ID: 'backoffice',
      BFF_OIDC_CLIENT_SECRET: 'client-secret',
      BFF_SESSION_SECRET: 'session-secret',
      BFF_PUBLIC_URL: 'http://localhost:4000',
      GATEWAY_URI: 'http://localhost:8081',
      REDIS_URL: 'redis://localhost:6379',
    });
  });

  it('rejects a missing required runtime value', () => {
    expect(() => readBackofficeConfig(new ConfigService({}))).toThrow(
      'Configuration key "BFF_SESSION_SECRET" does not exist',
    );
  });
});
