import { Test } from '@nestjs/testing';
import { ConfigModule, ConfigService } from '@nestjs/config';
import * as request from 'supertest';
import { INestApplication } from '@nestjs/common';
import { Controller, Get, Req } from '@nestjs/common';
import { readBackofficeConfig } from '../config/backoffice-config';
import { configureSession } from './configure-session';

@Controller()
class ProbeController {
  @Get('probe')
  probe(@Req() req: any) {
    req.session.hits = (req.session.hits || 0) + 1;
    return { hits: req.session.hits };
  }
}

describe('configureSession', () => {
  let app: INestApplication;

  beforeAll(async () => {
    Object.assign(process.env, {
      KEYCLOAK_APPS_ISSUER_URI: 'https://issuer.example/realms/Apps',
      BFF_OIDC_CLIENT_ID: 'backoffice',
      BFF_OIDC_CLIENT_SECRET: 'client-secret',
      BFF_SESSION_SECRET: 'test-secret',
      BFF_PUBLIC_URL: 'http://localhost:4000',
      GATEWAY_URI: 'http://localhost:8081',
      REDIS_URL: 'redis://localhost:6379',
    });
    const moduleRef = await Test.createTestingModule({
      imports: [ConfigModule.forRoot({ isGlobal: true })],
      controllers: [ProbeController],
    }).compile();
    app = moduleRef.createNestApplication();
    configureSession(app, readBackofficeConfig(app.get(ConfigService)));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('persists session state across requests via the session cookie', async () => {
    const agent = request.agent(app.getHttpServer());
    const first = await agent.get('/probe');
    const second = await agent.get('/probe');

    expect(first.body.hits).toBe(1);
    expect(second.body.hits).toBe(2);
    expect(first.headers['set-cookie'][0]).not.toContain('Secure');
  });
});
