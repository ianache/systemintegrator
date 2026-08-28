import {
  Body,
  CanActivate,
  Controller,
  Delete,
  ExecutionContext,
  Get,
  HttpCode,
  HttpStatus,
  Injectable,
  Param,
  Post,
  Put,
  Query,
  Req,
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
  getIntegrationProfiles(@Req() request: AuthenticatedRequest, @Query('activeOnly') activeOnly = 'true') {
    return this.gatewayProxy.getIntegrationProfiles(request.session.tokens!.access_token!, activeOnly !== 'false');
  }

  @Get('integration-profiles/:profileId')
  getIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.getIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles')
  @HttpCode(HttpStatus.OK)
  createIntegrationProfile(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.gatewayProxy.createIntegrationProfile(request.session.tokens!.access_token!, body);
  }

  @Put('integration-profiles/:profileId')
  updateIntegrationProfile(
    @Req() request: AuthenticatedRequest,
    @Param('profileId') profileId: string,
    @Body() body: unknown,
  ) {
    return this.gatewayProxy.updateIntegrationProfile(request.session.tokens!.access_token!, profileId, body);
  }

  @Delete('integration-profiles/:profileId')
  @HttpCode(HttpStatus.NO_CONTENT)
  deactivateIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.deactivateIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/sync')
  @HttpCode(HttpStatus.OK)
  triggerSync(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.triggerSync(request.session.tokens!.access_token!, profileId);
  }

  @Post('inbox/dlq/replay')
  @HttpCode(HttpStatus.OK)
  replayDeadLetterQueue(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.replayDeadLetterQueue(request.session.tokens!.access_token!);
  }

  @Get('messages')
  getMessages(@Req() request: AuthenticatedRequest, @Query('status') status = 'ALL') {
    return this.gatewayProxy.getMessages(request.session.tokens!.access_token!, status);
  }

  @Get('messages/:direction/:id')
  getMessage(
    @Req() request: AuthenticatedRequest,
    @Param('direction') direction: string,
    @Param('id') id: string,
  ) {
    return this.gatewayProxy.getMessage(request.session.tokens!.access_token!, direction, id);
  }

  @Post('messages/:direction/:id/retry')
  @HttpCode(HttpStatus.OK)
  retryMessage(
    @Req() request: AuthenticatedRequest,
    @Param('direction') direction: string,
    @Param('id') id: string,
  ) {
    return this.gatewayProxy.retryMessage(request.session.tokens!.access_token!, direction, id);
  }

  @Post('messages/:direction/:id/dlq')
  @HttpCode(HttpStatus.OK)
  moveMessageToDlq(
    @Req() request: AuthenticatedRequest,
    @Param('direction') direction: string,
    @Param('id') id: string,
  ) {
    return this.gatewayProxy.moveMessageToDlq(request.session.tokens!.access_token!, direction, id);
  }
}
