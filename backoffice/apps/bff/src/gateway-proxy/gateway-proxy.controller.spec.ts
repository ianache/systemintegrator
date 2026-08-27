import { HttpStatus, INestApplication, RequestMethod } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import axios from 'axios';
import request from 'supertest';
import { GatewayProxyModule } from './gateway-proxy.module';

describe('Gateway profile proxy', () => {
  let app: INestApplication;

  beforeEach(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({
          isGlobal: true,
          ignoreEnvFile: true,
          load: [() => ({ GATEWAY_URI: 'http://gateway.internal' })],
        }),
        GatewayProxyModule,
      ],
    }).compile();

    app = moduleRef.createNestApplication();
    app.use((req: any, _res: unknown, next: () => void) => {
      if (req.headers.cookie === 'session=authenticated') {
        req.session = {
          tokens: {
            access_token: 'session-access-token',
            id_token: 'server-only-id-token',
            tenantId: 'server-side-tenant',
            expiresAt: 1893456000,
          },
        };
      }
      next();
    });
    app.setGlobalPrefix('api', {
      exclude: [
        { path: 'bff/api/v1/integration-profiles', method: RequestMethod.GET },
        { path: 'bff/api/v1/integration-profiles', method: RequestMethod.POST },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.GET },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.PUT },
        { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.DELETE },
        { path: 'bff/api/v1/integration-profiles/:profileId/sync', method: RequestMethod.POST },
        { path: 'bff/api/v1/inbox/dlq/replay', method: RequestMethod.POST },
      ],
    });
    await app.init();
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app.close();
  });

  it('rejects an anonymous browser session on every route', async () => {
    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED)
      .expect({ statusCode: HttpStatus.UNAUTHORIZED, message: 'Authentication required', error: 'Unauthorized' });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED);
  });

  it('forwards the activeOnly filter and only the session access token', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ id: 'profile-1' }] });

    const response = await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles?activeOnly=false')
      .set('X-Tenant-ID', 'browser-controlled-tenant')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(response.body).toEqual([{ id: 'profile-1' }]);
    expect(get).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles?activeOnly=false', {
      headers: { Authorization: 'Bearer session-access-token' },
    });
  });

  it('gets a single profile by id', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { id: 'profile-1' } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { id: 'profile-1' });
  });

  it('creates a profile, forwarding the request body', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'profile-1' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .send({ businessDomain: 'vehicle' })
      .expect(HttpStatus.OK, { id: 'profile-1' });

    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles',
      { businessDomain: 'vehicle' },
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('updates a profile', async () => {
    jest.spyOn(axios, 'put').mockResolvedValue({ data: { id: 'profile-1', version: 8 } });

    await request(app.getHttpServer())
      .put('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .send({ expectedVersion: 7 })
      .expect(HttpStatus.OK, { id: 'profile-1', version: 8 });
  });

  it('deactivates a profile with a 204 response', async () => {
    jest.spyOn(axios, 'delete').mockResolvedValue({ data: undefined });

    await request(app.getHttpServer())
      .delete('/bff/api/v1/integration-profiles/profile-1')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.NO_CONTENT);
  });

  it('triggers a sync', async () => {
    jest.spyOn(axios, 'post').mockResolvedValue({ data: { profileId: 'profile-1', status: 'TRIGGERED' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles/profile-1/sync')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { profileId: 'profile-1', status: 'TRIGGERED' });
  });

  it('replays the dead letter queue', async () => {
    jest.spyOn(axios, 'post').mockResolvedValue({ data: { total: 1, success: 1, failed: 0 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/inbox/dlq/replay')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { total: 1, success: 1, failed: 0 });
  });

  it.each([
    [HttpStatus.UNAUTHORIZED, 'Gateway rejected the session credentials', 'Unauthorized'],
    [HttpStatus.FORBIDDEN, 'Gateway denied access to integration profiles', 'Forbidden'],
    [HttpStatus.BAD_GATEWAY, 'Gateway is unavailable', 'Bad Gateway'],
  ])('maps downstream failure to a stable browser error (%i)', async (status, message, error) => {
    jest.spyOn(axios, 'get').mockRejectedValue({ response: status === HttpStatus.BAD_GATEWAY ? undefined : { status } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(status)
      .expect({ statusCode: status, message, error });
  });

  it('passes through a 409 conflict body from the Gateway on create', async () => {
    const problem = { title: 'Conflict', status: 409, detail: 'An active integration profile already exists', errorCode: 'INTEGRATION_PROFILE_CONFLICT' };
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 409, data: problem } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .send({ businessDomain: 'vehicle' })
      .expect(409, problem);
  });

  it('passes through a 404 not-found body from the Gateway on get-by-id', async () => {
    const problem = { title: 'Not Found', status: 404, errorCode: 'INTEGRATION_PROFILE_NOT_FOUND' };
    jest.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404, data: problem } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles/missing')
      .set('Cookie', 'session=authenticated')
      .expect(404, problem);
  });
});
