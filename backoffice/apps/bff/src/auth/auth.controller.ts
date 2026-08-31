import { BadRequestException, Controller, Get, Req, Res } from '@nestjs/common';
import type { Request, Response } from 'express';
import { AuthService } from './auth.service';
import { projectSession } from './session-types';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Get('login')
  async login(@Req() req: Request, @Res() res: Response) {
    const { url, codeVerifier, state } = await this.authService.buildAuthorizationUrl();
    // The verifier and state stay server-side in the Redis-backed session; the
    // callback (Task 8) reads them back to complete the code exchange.
    req.session.oidc = { codeVerifier, state };
    res.redirect(url);
  }

  @Get('callback')
  async callback(@Req() req: Request, @Res() res: Response) {
    const code = typeof req.query.code === 'string' ? req.query.code : undefined;
    const state = typeof req.query.state === 'string' ? req.query.state : undefined;
    const issuer = typeof req.query.iss === 'string' ? req.query.iss : undefined;
    const authorization = req.session.oidc;

    if (!code || !state || !authorization || state !== authorization.state) {
      throw new BadRequestException('Invalid OIDC callback state');
    }

    req.session.tokens = await this.authService.completeAuthorizationCallback(
      code,
      state,
      authorization.codeVerifier,
      issuer,
    );
    req.session.cookie.maxAge = Math.max(
      0,
      req.session.tokens.expiresAt * 1000 - Date.now(),
    );
    delete req.session.oidc;
    res.redirect('/');
  }

  @Get('session')
  session(@Req() req: Request) {
    return projectSession(req.session.tokens);
  }

  @Get('logout')
  async logout(@Req() req: Request, @Res() res: Response) {
    await new Promise<void>((resolve, reject) => {
      req.session.destroy((error) => (error ? reject(error) : resolve()));
    });
    res.redirect('/');
  }
}
