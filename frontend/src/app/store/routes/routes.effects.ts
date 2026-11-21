import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { RouteApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RoutesActions } from './routes.actions';

export const loadRoutes = createEffect(
  (actions$ = inject(Actions), api = inject(RouteApi)) =>
    actions$.pipe(
      ofType(RoutesActions.load),
      switchMap(() =>
        api.list(0, 1000).pipe(
          map((page) => RoutesActions.loadSuccess({ routes: page.content })),
          catchError((err: HttpErrorResponse) =>
            of(RoutesActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
