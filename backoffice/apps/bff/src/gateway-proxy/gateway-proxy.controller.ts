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
import { AuthService } from '../auth/auth.service';
import type { SessionTokens } from '../auth/session-types';
import { GatewayProxyService } from './gateway-proxy.service';

interface AuthenticatedRequest extends Request {
  session: Request['session'] & {
    tokens?: SessionTokens;
  };
}

// How far ahead of the token's real expiry we treat it as "expiring soon" and
// refresh it proactively, so an in-flight request never races the Gateway
// rejecting a token that expired a few seconds into the round trip.
const REFRESH_SKEW_MS = 30_000;

@Injectable()
export class SessionAccessTokenGuard implements CanActivate {
  constructor(private readonly authService: AuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const tokens = request.session?.tokens;
    if (typeof tokens?.access_token !== 'string') {
      throw new UnauthorizedException('Authentication required');
    }

    const expiringSoon = tokens.expiresAt * 1000 - Date.now() <= REFRESH_SKEW_MS;
    if (!expiringSoon) {
      return true;
    }

    if (typeof tokens.refresh_token !== 'string') {
      throw new UnauthorizedException('Session expired');
    }

    try {
      request.session.tokens = await this.authService.refreshAccessToken(tokens.refresh_token);
      return true;
    } catch {
      throw new UnauthorizedException('Session expired');
    }
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

  @Post('integration-profiles/:profileId/pause')
  pauseIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.pauseIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/resume')
  resumeIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.resumeIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/sync')
  @HttpCode(HttpStatus.OK)
  triggerSync(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.triggerSync(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/mapping/dry-run')
  @HttpCode(HttpStatus.OK)
  mappingDryRun(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string, @Body() body: unknown) {
    return this.gatewayProxy.mappingDryRun(request.session.tokens!.access_token!, profileId, body);
  }

  @Post('integration-profiles/:profileId/extraction/dry-run')
  @HttpCode(HttpStatus.OK)
  extractionDryRun(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.extractionDryRun(request.session.tokens!.access_token!, profileId);
  }

  @Post('inbox/dlq/replay')
  @HttpCode(HttpStatus.OK)
  replayDeadLetterQueue(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.replayDeadLetterQueue(request.session.tokens!.access_token!);
  }

  @Get('messages')
  getMessages(
    @Req() request: AuthenticatedRequest,
    @Query('status') status = 'ALL',
    @Query('domain') domain?: string,
    @Query('from') from?: string,
    @Query('to') to?: string,
  ) {
    return this.gatewayProxy.getMessages(request.session.tokens!.access_token!, status, domain, from, to);
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

  @Get('credentials')
  getCredentials(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.getCredentials(request.session.tokens!.access_token!);
  }

  @Get('flows')
  getFlows(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.getFlows(request.session.tokens!.access_token!);
  }

  @Get('flows/:flowId')
  getFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.getFlow(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows')
  @HttpCode(HttpStatus.CREATED)
  createFlow(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.gatewayProxy.createFlow(request.session.tokens!.access_token!, body);
  }

  @Put('flows/:flowId')
  updateFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string, @Body() body: unknown) {
    return this.gatewayProxy.updateFlow(request.session.tokens!.access_token!, flowId, body);
  }

  @Get('flows/:flowId/versions')
  listFlowVersions(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.listFlowVersions(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows/:flowId/versions/publish')
  @HttpCode(HttpStatus.CREATED)
  publishFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.publishFlow(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows/:flowId/versions/:versionNumber/rollback')
  rollbackFlow(
    @Req() request: AuthenticatedRequest,
    @Param('flowId') flowId: string,
    @Param('versionNumber') versionNumber: string,
  ) {
    return this.gatewayProxy.rollbackFlow(request.session.tokens!.access_token!, flowId, Number(versionNumber));
  }

  @Get('flows/metrics/summary')
  getFlowMetricsSummary(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.getFlowMetricsSummary(request.session.tokens!.access_token!);
  }

  @Post('flows/:flowId/executions')
  @HttpCode(HttpStatus.CREATED)
  reportFlowExecution(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string, @Body() body: unknown) {
    return this.gatewayProxy.reportFlowExecution(request.session.tokens!.access_token!, flowId, body);
  }

  @Get('flows/:flowId/executions')
  listFlowExecutions(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.listFlowExecutions(request.session.tokens!.access_token!, flowId);
  }

  @Get('flows/:flowId/executions/:executionId')
  getFlowExecution(
    @Req() request: AuthenticatedRequest,
    @Param('flowId') flowId: string,
    @Param('executionId') executionId: string,
  ) {
    return this.gatewayProxy.getFlowExecution(request.session.tokens!.access_token!, flowId, executionId);
  }

  @Delete('flows/:flowId')
  @HttpCode(HttpStatus.NO_CONTENT)
  archiveFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.archiveFlow(request.session.tokens!.access_token!, flowId);
  }

  @Post('transformations/preview')
  @HttpCode(HttpStatus.OK)
  previewTransformation(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.gatewayProxy.previewTransformation(request.session.tokens!.access_token!, body);
  }
}
