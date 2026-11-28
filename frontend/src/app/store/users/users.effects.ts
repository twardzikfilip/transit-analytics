import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, concatMap, map, mergeMap, of, switchMap } from 'rxjs';
import { UsersApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { UsersActions } from './users.actions';

const failure = (err: HttpErrorResponse) => of(UsersActions.failure({ error: errorMessage(err) }));

export const load = createEffect(
  (actions$ = inject(Actions), api = inject(UsersApi)) =>
    actions$.pipe(
      ofType(UsersActions.load),
      switchMap(() =>
        api.list().pipe(
          map((users) => UsersActions.loaded({ users })),
          catchError(failure),
        ),
      ),
    ),
  { functional: true },
);

/** Adds a user; a taken name or a broken rule is a toast (409, 400). */
export const create = createEffect(
  (actions$ = inject(Actions), api = inject(UsersApi)) =>
    actions$.pipe(
      ofType(UsersActions.create),
      concatMap(({ user }) =>
        api.create(user).pipe(
          map((created) => UsersActions.created({ user: created })),
          catchError(failure),
        ),
      ),
    ),
  { functional: true },
);

export const remove = createEffect(
  (actions$ = inject(Actions), api = inject(UsersApi)) =>
    actions$.pipe(
      ofType(UsersActions.delete),
      mergeMap(({ username }) =>
        api.delete(username).pipe(
          map(() => UsersActions.deleted({ username })),
          catchError(failure),
        ),
      ),
    ),
  { functional: true },
);
