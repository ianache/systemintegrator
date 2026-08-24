import {
  CanActivate,
  Controller,
  ExecutionContext,
  Get,
  Injectable,
  UnauthorizedException,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import { GatewayProxyService } from './gateway-proxy.service';

interface AuthenticatedRequest extends Request {
  session: Request['session'] & {
    tokens?: { access_token?: string };
  };
}

@Injectable()
export class SessionAccessTokenGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    if (typeof request.session?.tokens?.access_token !== 'string') {
      throw new UnauthorizedException('Authentication required');
    }
    return true;
  }
}

@Controller('bff/api/v1')
@UseGuards(SessionAccessTokenGuard)
export class GatewayProxyController {
  constructor(private readonly gatewayProxy: GatewayProxyService) {}

  @Get('integration-profiles')
  getIntegrationProfiles(request: AuthenticatedRequest) {
    return this.gatewayProxy.getIntegrationProfiles(request.session.tokens!.access_token!);
  }
}
