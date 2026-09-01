import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { GatewayProxyController, SessionAccessTokenGuard } from './gateway-proxy.controller';
import { GatewayProxyService } from './gateway-proxy.service';

@Module({
  imports: [AuthModule],
  controllers: [GatewayProxyController],
  providers: [GatewayProxyService, SessionAccessTokenGuard],
})
export class GatewayProxyModule {}
