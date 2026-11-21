import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { LineStatsApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { LineStatsActions } from './line-stats.actions';

export const loadLineStats = createEffect(
  (actions$ = inject(Actions), api = inject(LineStatsApi)) =>
    actions$.pipe(
      ofType(LineStatsActions.load),
      switchMap(() =>
        api.weekday().pipe(
          map((report) => LineStatsActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(LineStatsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
