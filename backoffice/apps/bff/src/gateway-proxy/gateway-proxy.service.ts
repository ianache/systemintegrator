import {
  BadGatewayException,
  ForbiddenException,
  HttpException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

type HttpMethod = 'get' | 'post' | 'put' | 'delete';
const PASSTHROUGH_STATUSES = new Set([400, 404, 409, 422]);

@Injectable()
export class GatewayProxyService {
  constructor(private readonly config: ConfigService) {}

  getIntegrationProfiles(accessToken: string, activeOnly: boolean): Promise<unknown> {
    return this.forward('get', `/api/v1/integration-profiles?activeOnly=${activeOnly}`, accessToken);
  }

  getIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/integration-profiles/${profileId}`, accessToken);
  }

  createIntegrationProfile(accessToken: string, body: unknown): Promise<unknown> {
    return this.forward('post', '/api/v1/integration-profiles', accessToken, body);
  }

  updateIntegrationProfile(accessToken: string, profileId: string, body: unknown): Promise<unknown> {
    return this.forward('put', `/api/v1/integration-profiles/${profileId}`, accessToken, body);
  }

  deactivateIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('delete', `/api/v1/integration-profiles/${profileId}`, accessToken);
  }

  pauseIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/pause`, accessToken, {});
  }

  resumeIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/resume`, accessToken, {});
  }

  triggerSync(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/sync`, accessToken, {});
  }

  mappingDryRun(accessToken: string, profileId: string, body: unknown): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/mapping/dry-run`, accessToken, body);
  }

  extractionDryRun(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/extraction/dry-run`, accessToken, {});
  }

  replayDeadLetterQueue(accessToken: string): Promise<unknown> {
    return this.forward('post', '/api/v1/inbox/dlq/replay', accessToken, {});
  }

  getMessages(accessToken: string, status: string): Promise<unknown> {
    return this.forward('get', `/api/v1/messages?status=${encodeURIComponent(status)}`, accessToken);
  }

  getMessage(accessToken: string, direction: string, id: string): Promise<unknown> {
    return this.forward('get', `/api/v1/messages/${direction}/${id}`, accessToken);
  }

  retryMessage(accessToken: string, direction: string, id: string): Promise<unknown> {
    return this.forward('post', `/api/v1/messages/${direction}/${id}/retry`, accessToken, {});
  }

  moveMessageToDlq(accessToken: string, direction: string, id: string): Promise<unknown> {
    return this.forward('post', `/api/v1/messages/${direction}/${id}/dlq`, accessToken, {});
  }

  getCredentials(accessToken: string): Promise<unknown> {
    return this.forward('get', '/api/v1/credentials', accessToken);
  }

  getFlows(accessToken: string): Promise<unknown> {
    return this.forward('get', '/api/v1/flows', accessToken);
  }

  getFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/flows/${flowId}`, accessToken);
  }

  createFlow(accessToken: string, body: unknown): Promise<unknown> {
    return this.forward('post', '/api/v1/flows', accessToken, body);
  }

  updateFlow(accessToken: string, flowId: string, body: unknown): Promise<unknown> {
    return this.forward('put', `/api/v1/flows/${flowId}`, accessToken, body);
  }

  listFlowVersions(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/flows/${flowId}/versions`, accessToken);
  }

  publishFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/versions/publish`, accessToken, {});
  }

  rollbackFlow(accessToken: string, flowId: string, versionNumber: number): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/versions/${versionNumber}/rollback`, accessToken, {});
  }

  getFlowMetricsSummary(accessToken: string): Promise<unknown> {
    return this.forward('get', '/api/v1/flows/metrics/summary', accessToken);
  }

  reportFlowExecution(accessToken: string, flowId: string, body: unknown): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/executions`, accessToken, body);
  }

  archiveFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('delete', `/api/v1/flows/${flowId}`, accessToken);
  }

  private async forward(method: HttpMethod, path: string, accessToken: string, body?: unknown): Promise<unknown> {
    const url = `${this.config.getOrThrow<string>('GATEWAY_URI')}${path}`;
    const options = { headers: { Authorization: `Bearer ${accessToken}` } };

    try {
      const response =
        method === 'get'
          ? await axios.get(url, options)
          : method === 'delete'
            ? await axios.delete(url, options)
            : await axios[method](url, body, options);
      return response.data;
    } catch (error) {
      throw this.mapError(error);
    }
  }

  private mapError(error: unknown): Error {
    const response = (error as { response?: { status?: number; data?: unknown } }).response;
    const status = response?.status;

    if (status === 401) {
      return new UnauthorizedException('Gateway rejected the session credentials');
    }
    if (status === 403) {
      return new ForbiddenException('Gateway denied access to integration profiles');
    }
    if (status !== undefined && PASSTHROUGH_STATUSES.has(status)) {
      return new HttpException(response?.data as Record<string, unknown>, status);
    }
    return new BadGatewayException('Gateway is unavailable');
  }
}
