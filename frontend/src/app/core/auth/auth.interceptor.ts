import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { catchError, throwError } from 'rxjs';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';

/** Adds the Bearer token to API calls and logs the user out when the backend answers 401. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const store = inject(Store);
  const token = store.selectSignal(authFeature.selectToken)();
  const isLogin = req.url.endsWith('/api/auth/login');

  const authReq =
    token && !isLogin ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !isLogin) {
        store.dispatch(AuthActions.logout());
      }
      return throwError(() => error);
    }),
  );
};
