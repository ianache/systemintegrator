import type { NestExpressApplication } from '@nestjs/platform-express';
import type { Request, Response, NextFunction } from 'express';
import { join } from 'node:path';

const shellStaticPath = join(__dirname, '..', 'shell-static');
const shellIndexPath = join(shellStaticPath, 'index.html');
const apiRoutePattern = /^\/(?:api|auth|bff\/api)(?:\/|$)/;

export function configureStaticShell(app: NestExpressApplication): void {
  app.useStaticAssets(shellStaticPath, { index: false });
  app.use((request: Request, response: Response, next: NextFunction) => {
    if (
      request.method !== 'GET' &&
      request.method !== 'HEAD' ||
      apiRoutePattern.test(request.path)
    ) {
      next();
      return;
    }

    response.sendFile(shellIndexPath, (error) => {
      if (error) {
        next(error);
      }
    });
  });
}
