import type { NestExpressApplication } from '@nestjs/platform-express';
import type { Request, Response, NextFunction } from 'express';
import { join } from 'node:path';

const shellStaticPath = join(__dirname, 'shell-static');
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

    // `res.sendFile()` runs its `path` argument through `encodeURI()`, which
    // mangles Windows backslash-separated absolute paths (turns every \ into
    // %5C), making send() 404 on a file that demonstrably exists. Passing
    // `root` with a relative filename avoids the encoding path entirely.
    response.sendFile('index.html', { root: shellStaticPath }, (error) => {
      if (error) {
        next(error);
      }
    });
  });
}
