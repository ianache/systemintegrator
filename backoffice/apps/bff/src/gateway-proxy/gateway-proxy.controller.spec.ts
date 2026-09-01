import { HttpStatus, INestApplication, RequestMethod } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import axios from 'axios';
import request from 'supertest';
import { AuthService } from '../auth/auth.service';
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
        { path: 'bff/api/v1/integration-profiles/:profileId/mapping/dry-run', method: RequestMethod.POST },
        { path: 'bff/api/v1/flows', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows', method: RequestMethod.POST },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.PUT },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.DELETE },
        { path: 'bff/api/v1/flows/:flowId/versions', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows/:flowId/versions/publish', method: RequestMethod.POST },
        { path: 'bff/api/v1/flows/:flowId/versions/:versionNumber/rollback', method: RequestMethod.POST },
        { path: 'bff/api/v1/inbox/dlq/replay', method: RequestMethod.POST },
        { path: 'bff/api/v1/messages', method: RequestMethod.GET },
        { path: 'bff/api/v1/messages/:direction/:id', method: RequestMethod.GET },
        { path: 'bff/api/v1/messages/:direction/:id/retry', method: RequestMethod.POST },
        { path: 'bff/api/v1/messages/:direction/:id/dlq', method: RequestMethod.POST },
        { path: 'bff/api/v1/credentials', method: RequestMethod.GET },
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

  it('lists messages filtered by status', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ id: 'msg-1', status: 'DLQ' }] });

    const response = await request(app.getHttpServer())
      .get('/bff/api/v1/messages?status=DLQ')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(response.body).toEqual([{ id: 'msg-1', status: 'DLQ' }]);
    expect(get).toHaveBeenCalledWith('http://gateway.internal/api/v1/messages?status=DLQ', {
      headers: { Authorization: 'Bearer session-access-token' },
    });
  });

  it('gets a single message by direction and id', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { id: 'msg-1', payload: '{}' } });

    await request(app.getHttpServer())
      .get('/bff/api/v1/messages/INBOUND/msg-1')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { id: 'msg-1', payload: '{}' });
  });

  it('retries a message', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'msg-1', status: 'PENDING' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/messages/OUTBOUND/msg-1/retry')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { id: 'msg-1', status: 'PENDING' });

    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/messages/OUTBOUND/msg-1/retry',
      {},
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('moves a message to the DLQ', async () => {
    jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'msg-1', status: 'DLQ' } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/messages/OUTBOUND/msg-1/dlq')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, { id: 'msg-1', status: 'DLQ' });
  });

  it('runs a mapping dry-run, forwarding the payload and transformation to the Gateway', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { output: '{"a":1}', error: null } });

    const response = await request(app.getHttpServer())
      .post('/bff/api/v1/integration-profiles/profile-1/mapping/dry-run')
      .set('Cookie', 'session=authenticated')
      .send({ payload: '{"a":1}', transformationJson: '{"engine":"PASSTHROUGH"}' })
      .expect(HttpStatus.OK);

    expect(response.body).toEqual({ output: '{"a":1}', error: null });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles/profile-1/mapping/dry-run',
      { payload: '{"a":1}', transformationJson: '{"engine":"PASSTHROUGH"}' },
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('lists credentials', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ ref: 'secret/cl2/cred', state: 'VIGENTE' }] });

    await request(app.getHttpServer())
      .get('/bff/api/v1/credentials')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK, [{ ref: 'secret/cl2/cred', state: 'VIGENTE' }]);
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

  it('proxies flow creation with the session access token', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'f-1', code: 'flow/x' } });

    const response = await request(app.getHttpServer())
      .post('/bff/api/v1/flows')
      .set('Cookie', 'session=authenticated')
      .send({ code: 'flow/x', name: 'X' });

    expect(response.status).toBe(HttpStatus.CREATED === undefined ? 201 : HttpStatus.CREATED);
    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows',
      { code: 'flow/x', name: 'X' },
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('proxies flow listing with the session access token', async () => {
    const getSpy = jest.spyOn(axios, 'get').mockResolvedValue({ data: [] });

    await request(app.getHttpServer()).get('/bff/api/v1/flows').set('Cookie', 'session=authenticated');

    expect(getSpy).toHaveBeenCalledWith('http://gateway.internal/api/v1/flows', {
      headers: { Authorization: 'Bearer session-access-token' },
    });
  });

  it('proxies publishing a flow version', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { versionNumber: 1 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/flows/f-1/versions/publish')
      .set('Cookie', 'session=authenticated');

    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows/f-1/versions/publish',
      {},
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('proxies rolling back a flow to an earlier version', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { versionNumber: 1 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/flows/f-1/versions/1/rollback')
      .set('Cookie', 'session=authenticated');

    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows/f-1/versions/1/rollback',
      {},
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });
});

describe('Gateway proxy silent access-token refresh', () => {
  let app: INestApplication;
  let refreshAccessToken: jest.Mock;

  const buildApp = async (sessionTokens: Record<string, unknown>) => {
    refreshAccessToken = jest.fn();
    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({
          isGlobal: true,
          ignoreEnvFile: true,
          load: [() => ({ GATEWAY_URI: 'http://gateway.internal' })],
        }),
        GatewayProxyModule,
      ],
    })
      .overrideProvider(AuthService)
      .useValue({ refreshAccessToken })
      .compile();

    const testApp = moduleRef.createNestApplication();
    testApp.use((req: any, _res: unknown, next: () => void) => {
      if (req.headers.cookie === 'session=authenticated') {
        req.session = { tokens: sessionTokens };
      }
      next();
    });
    await testApp.init();
    return testApp;
  };

  afterEach(async () => {
    jest.restoreAllMocks();
    await app.close();
  });

  it('refreshes an access token that is about to expire before forwarding the request', async () => {
    app = await buildApp({
      access_token: 'stale-access-token',
      refresh_token: 'refresh-token-1',
      tenantId: 'tenant-a',
      expiresAt: Math.floor(Date.now() / 1000) + 5, // 5s out — inside the refresh skew window
    });
    refreshAccessToken.mockResolvedValue({
      access_token: 'fresh-access-token',
      id_token: 'fresh-id-token',
      refresh_token: 'refresh-token-2',
      tenantId: 'tenant-a',
      expiresAt: Math.floor(Date.now() / 1000) + 3600,
    });
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [] });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(refreshAccessToken).toHaveBeenCalledWith('refresh-token-1');
    expect(get).toHaveBeenCalledWith(expect.any(String), {
      headers: { Authorization: 'Bearer fresh-access-token' },
    });
  });

  it('does not refresh a token that is still comfortably valid', async () => {
    app = await buildApp({
      access_token: 'still-valid-access-token',
      refresh_token: 'refresh-token-1',
      tenantId: 'tenant-a',
      expiresAt: Math.floor(Date.now() / 1000) + 3600,
    });
    jest.spyOn(axios, 'get').mockResolvedValue({ data: [] });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(refreshAccessToken).not.toHaveBeenCalled();
  });

  it('rejects with 401 when the token is expiring and there is no refresh token', async () => {
    app = await buildApp({
      access_token: 'stale-access-token',
      tenantId: 'tenant-a',
      expiresAt: Math.floor(Date.now() / 1000) - 10,
    });

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.UNAUTHORIZED);

    expect(refreshAccessToken).not.toHaveBeenCalled();
  });

  it('rejects with 401 when Keycloak rejects the refresh token (expired or revoked)', async () => {
    app = await buildApp({
      access_token: 'stale-access-token',
      refresh_token: 'revoked-refresh-token',
      tenantId: 'tenant-a',
      expiresAt: Math.floor(Date.now() / 1000) - 10,
    });
    refreshAccessToken.mockRejectedValue(new Error('invalid_grant'));

    await request(app.getHttpServer())
      .get('/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.UNAUTHORIZED);
  });
});
