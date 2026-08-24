import { INestApplication } from '@nestjs/common';
import session from 'express-session';
import { createClient } from 'redis';
import { RedisStore } from 'connect-redis';
import { BackofficeConfig } from '../config/backoffice-config';

export function configureSession(
  app: INestApplication,
  config: BackofficeConfig,
): void {
  const redisClient = createClient({ url: config.REDIS_URL });
  redisClient.connect().catch((error) => {
    throw error;
  });

  // Release the Redis connection when the Nest app is explicitly shut down
  // (e.g. app.close() in tests, or graceful process shutdown), otherwise it
  // leaks past the app's lifecycle. Deliberately hooked on app.close() rather
  // than the underlying HTTP server's 'close' event: supertest opens/closes
  // that raw server around every individual request when the app was never
  // given its own app.listen(), so a listener there would kill the shared
  // Redis client mid-test.
  const originalClose = app.close.bind(app);
  app.close = async () => {
    await redisClient.quit().catch(() => undefined);
    return originalClose();
  };

  app.use(
    session({
      store: new RedisStore({ client: redisClient, prefix: 'backoffice-session:' }),
      secret: config.BFF_SESSION_SECRET,
      resave: false,
      saveUninitialized: false,
      cookie: {
        httpOnly: true,
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'lax',
        maxAge: 8 * 60 * 60 * 1000,
      },
    }),
  );
}
