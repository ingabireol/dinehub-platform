import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Attaches the bearer token and handles a 401 once, centrally.
 *
 * <p>Without this, every component would need its own "did the session expire?"
 * branch, and they would not all get it right.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const token = auth.accessToken;
  const authorised = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorised).pipe(
    catchError((error: HttpErrorResponse) => {
      // 401 means the token is gone or expired. Log out and send them to the
      // login page rather than leaving a half-authenticated UI showing stale
      // data it can no longer refresh.
      if (error.status === 401 && !request.url.includes('/auth/')) {
        auth.logout();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
