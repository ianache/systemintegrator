import type { NestExpressApplication } from '@nestjs/platform-express';
import type { Request, Response, NextFunction } from 'express';
import { join } from 'node:path';

const shellStaticPath = join(__dirname, '..', 'shell-static');
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

    // `res.sendFile()` runs its `path` argument through `encodeURI()` before
    // handing it to the `send` module. On Windows, that turns every `\` in an
    // absolute path (e.g. `C:\...\shell-static\index.html`) into `%5C`, which
    // `send` then fails to resolve back to a real file (reproducibly 404s
    // even though the file exists). Passing `root` + a relative filename
    // sidesteps the encoding entirely — `send` joins them without treating
    // the root as URI-decodable path text.
    response.sendFile('index.html', { root: shellStaticPath }, (error) => {
      if (error) {
        next(error);
      }
    });
  });
}
