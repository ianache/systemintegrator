import type { NestExpressApplication } from '@nestjs/platform-express';
import { HttpStatus, RequestMethod } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import request from 'supertest';
import { AppModule } from './app.module';
import { configureStaticShell } from './static-shell';

describe('BFF static Shell fallback', () => {
  const shellStaticPath = join(__dirname, 'shell-static');
  let app: NestExpressApplication;

  beforeAll(async () => {
    mkdirSync(shellStaticPath, { recursive: true });
    writeFileSync(
      join(shellStaticPath, 'index.html'),
      '<!doctype html><html><body>Backoffice Shell</body></html>',
    );

    const moduleRef = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();

    app = moduleRef.createNestApplication<NestExpressApplication>();
    app.setGlobalPrefix('api', {
      exclude: [
        { path: 'auth/login', method: RequestMethod.GET },
        { path: 'auth/callback', method: RequestMethod.GET },
        { path: 'auth/session', method: RequestMethod.GET },
        { path: 'auth/logout', method: RequestMethod.GET },
        {
          path: 'bff/api/v1/integration-profiles',
          method: RequestMethod.GET,
        },
      ],
    });
    configureStaticShell(app);
    await app.init();
  });

  afterAll(async () => {
    await app?.close();
    if (existsSync(shellStaticPath)) {
      rmSync(shellStaticPath, { recursive: true, force: true });
    }
  });

  it('serves the Shell entry point as HTML from the BFF origin', async () => {
    await request(app.getHttpServer())
      .get('/')
      .expect(200)
      .expect('Content-Type', /html/)
      .expect(/Backoffice Shell/);
  });

  it('serves the Shell entry point for the integration SPA route', async () => {
    await request(app.getHttpServer())
      .get('/integration')
      .expect(200)
      .expect('Content-Type', /html/)
      .expect(/Backoffice Shell/);
  });

  it('does not shadow the prefixed BFF API route', async () => {
    await request(app.getHttpServer())
      .get('/api')
      .expect(200)
      .expect('Content-Type', /json/)
      .expect({ message: 'Hello API' });
  });

  it('does not serve Shell HTML for an anonymous BFF API request', async () => {
    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED)
      .expect('Content-Type', /json/)
      .expect({
        statusCode: HttpStatus.UNAUTHORIZED,
        message: 'Authentication required',
        error: 'Unauthorized',
      });
  });

  it('does not serve Shell HTML for an auth route', async () => {
    await request(app.getHttpServer())
      .get('/auth/not-a-route')
      .expect(404)
      .expect('Content-Type', /json/);
  });
});
