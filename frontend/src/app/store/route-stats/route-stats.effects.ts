import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { RouteStatsApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RouteStatsActions } from './route-stats.actions';

export const loadRouteStats = createEffect(
  (actions$ = inject(Actions), api = inject(RouteStatsApi)) =>
    actions$.pipe(
      ofType(RouteStatsActions.load),
      switchMap(() =>
        api.weekday().pipe(
          map((report) => RouteStatsActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(RouteStatsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
