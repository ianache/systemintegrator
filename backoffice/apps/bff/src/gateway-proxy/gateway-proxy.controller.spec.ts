import { HttpStatus, INestApplication } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import axios from 'axios';
import request from 'supertest';
import { GatewayProxyModule } from './gateway-proxy.module';

describe('Gateway profile proxy', () => {
  let app: INestApplication;
  let get: jest.SpiedFunction<typeof axios.get>;

  beforeEach(async () => {
    get = jest.spyOn(axios, 'get');
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
    app.setGlobalPrefix('api');
    await app.init();
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app.close();
  });

  it('rejects an anonymous browser session', async () => {
    await request(app.getHttpServer())
      .get('/api/bff/api/v1/integration-profiles')
      .expect(HttpStatus.UNAUTHORIZED)
      .expect({
        statusCode: HttpStatus.UNAUTHORIZED,
        message: 'Authentication required',
        error: 'Unauthorized',
      });
  });

  it('forwards only the session access token to the allowlisted Gateway route', async () => {
    get.mockResolvedValue({ data: [{ id: 'profile-1' }] } as Awaited<
      ReturnType<typeof axios.get>
    >);

    const response = await request(app.getHttpServer())
      .get('/api/bff/api/v1/integration-profiles')
      .set('X-Tenant-ID', 'browser-controlled-tenant')
      .set('Cookie', 'session=authenticated')
      .expect(HttpStatus.OK);

    expect(response.body).toEqual([{ id: 'profile-1' }]);
    expect(get).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles',
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it.each([
    [HttpStatus.UNAUTHORIZED, 'Gateway rejected the session credentials', 'Unauthorized'],
    [HttpStatus.FORBIDDEN, 'Gateway denied access to integration profiles', 'Forbidden'],
    [HttpStatus.BAD_GATEWAY, 'Gateway is unavailable', 'Bad Gateway'],
  ])('maps downstream failure to a stable browser error (%i)', async (status, message, error) => {
    get.mockRejectedValue({ response: status === HttpStatus.BAD_GATEWAY ? undefined : { status } });

    await request(app.getHttpServer())
      .get('/api/bff/api/v1/integration-profiles')
      .set('Cookie', 'session=authenticated')
      .expect(status)
      .expect({ statusCode: status, message, error });
  });
});
