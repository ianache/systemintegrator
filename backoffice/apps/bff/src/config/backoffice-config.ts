import { ConfigService } from '@nestjs/config';

export type BackofficeConfig = {
  KEYCLOAK_APPS_ISSUER_URI: string;
  BFF_OIDC_CLIENT_ID: string;
  BFF_OIDC_CLIENT_SECRET: string;
  BFF_SESSION_SECRET: string;
  BFF_PUBLIC_URL: string;
  GATEWAY_URI: string;
  REDIS_URL: string;
};

export function readBackofficeConfig(config: ConfigService): BackofficeConfig {
  return {
    BFF_SESSION_SECRET: config.getOrThrow<string>('BFF_SESSION_SECRET'),
    KEYCLOAK_APPS_ISSUER_URI: config.getOrThrow<string>(
      'KEYCLOAK_APPS_ISSUER_URI',
    ),
    BFF_OIDC_CLIENT_ID: config.getOrThrow<string>('BFF_OIDC_CLIENT_ID'),
    BFF_OIDC_CLIENT_SECRET: config.getOrThrow<string>(
      'BFF_OIDC_CLIENT_SECRET',
    ),
    BFF_PUBLIC_URL: config.getOrThrow<string>('BFF_PUBLIC_URL'),
    GATEWAY_URI: config.getOrThrow<string>('GATEWAY_URI'),
    REDIS_URL: config.getOrThrow<string>('REDIS_URL'),
  };
}
