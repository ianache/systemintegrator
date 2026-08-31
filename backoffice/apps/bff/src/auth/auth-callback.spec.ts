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
      session: {
        oidc: { codeVerifier: 'verifier-1', state: 'expected-state' },
        cookie: { maxAge: 8 * 60 * 60 * 1000 },
      },
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
      session: {
        oidc: { codeVerifier: 'verifier-1', state: 'expected-state' },
        cookie: { maxAge: 8 * 60 * 60 * 1000 },
      },
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
      session: {
        oidc: { codeVerifier: 'verifier-1', state: 'expected-state' },
        cookie: { maxAge: 8 * 60 * 60 * 1000 },
      },
    };
    const res = { redirect: jest.fn() };

    await controller.callback(req, res as any);

    expect(authService.completeAuthorizationCallback).toHaveBeenCalledWith(
      'code-1',
      'expected-state',
      'verifier-1',
      undefined,
    );
    expect(req.session.tokens).toEqual(tokens);
    expect(req.session.oidc).toBeUndefined();
    expect(res.redirect).toHaveBeenCalledWith('/');
  });

  it('aligns the session cookie lifetime with the Keycloak token expiration', async () => {
    const tokens = {
      access_token: 'private-access-token',
      id_token: 'private-id-token',
      tenantId: 'tenant-a',
      expiresAt: 1_900,
    };
    const authService = {
      completeAuthorizationCallback: jest.fn().mockResolvedValue(tokens),
    };
    const controller = new AuthController(authService as unknown as AuthService);
    const req: any = {
      query: { code: 'code-1', state: 'expected-state' },
      session: {
        oidc: { codeVerifier: 'verifier-1', state: 'expected-state' },
        cookie: { maxAge: 8 * 60 * 60 * 1000 },
      },
    };
    const res = { redirect: jest.fn() };
    const now = 1_000_000;
    jest.spyOn(Date, 'now').mockReturnValue(now);

    try {
      await controller.callback(req, res as any);
      expect(req.session.cookie.maxAge).toBe(tokens.expiresAt * 1000 - now);
    } finally {
      jest.restoreAllMocks();
    }
  });

  it('forwards the issuer parameter received from Keycloak to the code exchange', async () => {
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
      originalUrl:
        '/auth/callback?code=code-1&state=expected-state&iss=https%3A%2F%2Foauth2.qa.comsatel.com.pe%2Frealms%2FApps',
      query: {
        code: 'code-1',
        state: 'expected-state',
        iss: 'https://oauth2.qa.comsatel.com.pe/realms/Apps',
      },
      session: {
        oidc: { codeVerifier: 'verifier-1', state: 'expected-state' },
        cookie: { maxAge: 8 * 60 * 60 * 1000 },
      },
    };

    await controller.callback(req, { redirect: jest.fn() } as any);

    expect(authService.completeAuthorizationCallback).toHaveBeenCalledWith(
      'code-1',
      'expected-state',
      'verifier-1',
      'https://oauth2.qa.comsatel.com.pe/realms/Apps',
    );
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
