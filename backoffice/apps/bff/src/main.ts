/**
 * This is not a production server yet!
 * This is only a minimal backend to get started.
 */

import { Logger, RequestMethod } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { ConfigService } from '@nestjs/config';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app/app.module';
import { readBackofficeConfig } from './config/backoffice-config';
import { configureSession } from './session/configure-session';
import { configureStaticShell } from './app/static-shell';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);
  const globalPrefix = 'api';
  app.setGlobalPrefix(globalPrefix, {
    exclude: [
      { path: 'auth/login', method: RequestMethod.GET },
      { path: 'auth/callback', method: RequestMethod.GET },
      { path: 'auth/session', method: RequestMethod.GET },
      { path: 'auth/logout', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles', method: RequestMethod.POST },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.GET },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.PUT },
      { path: 'bff/api/v1/integration-profiles/:profileId', method: RequestMethod.DELETE },
      { path: 'bff/api/v1/integration-profiles/:profileId/sync', method: RequestMethod.POST },
      { path: 'bff/api/v1/inbox/dlq/replay', method: RequestMethod.POST },
      { path: 'bff/api/v1/messages', method: RequestMethod.GET },
      { path: 'bff/api/v1/messages/:direction/:id', method: RequestMethod.GET },
      { path: 'bff/api/v1/messages/:direction/:id/retry', method: RequestMethod.POST },
      { path: 'bff/api/v1/messages/:direction/:id/dlq', method: RequestMethod.POST },
    ],
  });
  configureSession(app, readBackofficeConfig(app.get(ConfigService)));
  configureStaticShell(app);
  await app.init();
  const port = process.env.PORT || 3000;
  await app.listen(port);
  Logger.log(
    `🚀 Application is running on: http://localhost:${port}/${globalPrefix}`,
  );
}

bootstrap();
