import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { AppController } from './app.controller';
import { AppService } from './app.service';
import { AuthModule } from '../auth/auth.module';
import { GatewayProxyModule } from '../gateway-proxy/gateway-proxy.module';

@Module({
  imports: [ConfigModule.forRoot({ isGlobal: true }), AuthModule, GatewayProxyModule],
  controllers: [AppController],
  providers: [AppService],
})
export class AppModule {}
