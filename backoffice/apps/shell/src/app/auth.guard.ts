import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { map } from 'rxjs';
import { SessionService } from './session/session.service';

export const authGuard: CanActivateFn = () => {
  const sessionService = inject(SessionService);

  return sessionService.check().pipe(
    map((session) => {
      if (session.authenticated) {
        return true;
      }

      sessionService.login();
      return false;
    }),
  );
};
