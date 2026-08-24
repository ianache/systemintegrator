import {
  BadGatewayException,
  ForbiddenException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

@Injectable()
export class GatewayProxyService {
  constructor(private readonly config: ConfigService) {}

  async getIntegrationProfiles(accessToken: string): Promise<unknown> {
    try {
      const response = await axios.get(
        `${this.config.getOrThrow<string>('GATEWAY_URI')}/api/v1/integration-profiles`,
        { headers: { Authorization: `Bearer ${accessToken}` } },
      );
      return response.data;
    } catch (error) {
      const downstreamStatus = (error as { response?: { status?: number } }).response
        ?.status;

      if (downstreamStatus === 401) {
        throw new UnauthorizedException('Gateway rejected the session credentials');
      }
      if (downstreamStatus === 403) {
        throw new ForbiddenException('Gateway denied access to integration profiles');
      }
      throw new BadGatewayException('Gateway is unavailable');
    }
  }
}
