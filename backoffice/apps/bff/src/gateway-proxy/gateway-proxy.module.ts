import { Module } from '@nestjs/common';
import { GatewayProxyController, SessionAccessTokenGuard } from './gateway-proxy.controller';
import { GatewayProxyService } from './gateway-proxy.service';

@Module({
  controllers: [GatewayProxyController],
  providers: [GatewayProxyService, SessionAccessTokenGuard],
})
export class GatewayProxyModule {}
