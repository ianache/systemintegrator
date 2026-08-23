import { Controller, Get, Req, Res } from '@nestjs/common';
import type { Request, Response } from 'express';
import { AuthService } from './auth.service';

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
}
