import { Test } from '@nestjs/testing';
import { ConfigModule, ConfigService } from '@nestjs/config';
import * as request from 'supertest';
import { INestApplication } from '@nestjs/common';
import { Controller, Get, Req } from '@nestjs/common';
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
    process.env.BFF_SESSION_SECRET = 'test-secret';
    process.env.REDIS_URL = 'redis://localhost:6379';
    const moduleRef = await Test.createTestingModule({
      imports: [ConfigModule.forRoot({ isGlobal: true })],
      controllers: [ProbeController],
    }).compile();
    app = moduleRef.createNestApplication();
    configureSession(app, app.get(ConfigService));
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
  });
});
