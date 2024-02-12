import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';
import { Role } from './models';

/** Requires a logged-in user. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};

/**
 * Requires one of the given roles.
 *
 * <p>This is a usability control, not a security one. Every endpoint is
 * authorised on the server as well, which is what actually stops a determined
 * user — this just avoids showing them a page that will only produce a 403.
 */
export const roleGuard = (...allowed: Role[]): CanActivateFn => {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (!auth.isLoggedIn()) {
      return router.createUrlTree(['/login']);
    }
    const role = auth.role();
    if (role && allowed.includes(role)) {
      return true;
    }
    return router.createUrlTree(['/menu']);
  };
};
