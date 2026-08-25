import { BadRequestException } from '@nestjs/common';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

describe('AuthController.callback', () => {
  it('rejects a callback with state different from the stored authorization state before exchanging the code', async () => {
    const authService = { completeAuthorizationCallback: jest.fn() };
    const controller = new AuthController(authService as unknown as AuthService);
    const req: any = {
      originalUrl: '/auth/callback?code=code-1&state=wrong-state',
      query: { code: 'code-1', state: 'wrong-state' },
      session: { oidc: { codeVerifier: 'verifier-1', state: 'expected-state' } },
    };

    await expect(controller.callback(req, { redirect: jest.fn() } as any)).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(authService.completeAuthorizationCallback).not.toHaveBeenCalled();
  });

  it('rejects a callback without state before exchanging the code', async () => {
    const authService = { completeAuthorizationCallback: jest.fn() };
    const controller = new AuthController(authService as unknown as AuthService);
    const req: any = {
      originalUrl: '/auth/callback?code=code-1',
      query: { code: 'code-1' },
      session: { oidc: { codeVerifier: 'verifier-1', state: 'expected-state' } },
    };

    await expect(controller.callback(req, { redirect: jest.fn() } as any)).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(authService.completeAuthorizationCallback).not.toHaveBeenCalled();
  });

  it('stores exchanged tokens server-side and redirects to the Shell', async () => {
    const tokens = {
      access_token: 'private-access-token',
      id_token: 'private-id-token',
      tenantId: 'tenant-a',
      expiresAt: 1893456000,
    };
    const authService = {
      completeAuthorizationCallback: jest.fn().mockResolvedValue(tokens),
    };
    const controller = new AuthController(authService as unknown as AuthService);
    const req: any = {
      originalUrl: '/auth/callback?code=code-1&state=expected-state',
      query: { code: 'code-1', state: 'expected-state' },
      session: { oidc: { codeVerifier: 'verifier-1', state: 'expected-state' } },
    };
    const res = { redirect: jest.fn() };

    await controller.callback(req, res as any);

    expect(authService.completeAuthorizationCallback).toHaveBeenCalledWith(
      'code-1',
      'expected-state',
      'verifier-1',
    );
    expect(req.session.tokens).toEqual(tokens);
    expect(req.session.oidc).toBeUndefined();
    expect(res.redirect).toHaveBeenCalledWith('/');
  });

  it('destroys the server session before redirecting to the Shell on logout', async () => {
    const controller = new AuthController({} as AuthService);
    const destroy = jest.fn((callback) => callback());
    const res = { redirect: jest.fn() };

    await controller.logout({ session: { destroy } } as any, res as any);

    expect(destroy).toHaveBeenCalledTimes(1);
    expect(res.redirect).toHaveBeenCalledWith('/');
  });
});
