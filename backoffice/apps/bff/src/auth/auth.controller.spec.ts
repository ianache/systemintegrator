import { Test } from '@nestjs/testing';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

describe('AuthController.login', () => {
  it('stores the PKCE verifier and state in the session and redirects to Keycloak', async () => {
    const authService = {
      buildAuthorizationUrl: jest.fn().mockResolvedValue({
        url: 'https://oauth2.qa.comsatel.com.pe/realms/Apps/protocol/openid-connect/auth?...',
        codeVerifier: 'verifier-123',
        state: 'state-abc',
      }),
    };
    const moduleRef = await Test.createTestingModule({
      controllers: [AuthController],
      providers: [{ provide: AuthService, useValue: authService }],
    }).compile();
    const controller = moduleRef.get(AuthController);

    const req: any = { session: {} };
    const res: any = { redirect: jest.fn() };

    await controller.login(req, res);

    expect(req.session.oidc).toEqual({ codeVerifier: 'verifier-123', state: 'state-abc' });
    expect(res.redirect).toHaveBeenCalledWith(
      'https://oauth2.qa.comsatel.com.pe/realms/Apps/protocol/openid-connect/auth?...',
    );
  });
});
