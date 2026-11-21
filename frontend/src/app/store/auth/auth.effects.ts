import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, tap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { traceLogin } from '../../core/telemetry/login-telemetry';
import { AuthActions } from './auth.actions';

export const login = createEffect(
  (actions$ = inject(Actions), auth = inject(AuthService)) =>
    actions$.pipe(
      ofType(AuthActions.login),
      exhaustMap(({ username, password }) =>
        traceLogin(username, auth.login(username, password)).pipe(
          map((session) => AuthActions.loginSuccess({ session })),
          catchError((err: HttpErrorResponse) =>
            of(
              AuthActions.loginFailure({
                error: err.status === 401 ? 'Invalid username or password' : 'Login failed',
              }),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const loginSuccess = createEffect(
  (actions$ = inject(Actions), auth = inject(AuthService), router = inject(Router)) =>
    actions$.pipe(
      ofType(AuthActions.loginSuccess),
      tap(({ session }) => {
        auth.saveSession(session);
        router.navigateByUrl('/home');
      }),
    ),
  { functional: true, dispatch: false },
);

export const logout = createEffect(
  (actions$ = inject(Actions), auth = inject(AuthService), router = inject(Router)) =>
    actions$.pipe(
      ofType(AuthActions.logout),
      tap(() => {
        auth.clearSession();
        router.navigateByUrl('/login');
      }),
    ),
  { functional: true, dispatch: false },
);
