import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { TripAnomalyApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { TripAnomaliesActions } from './trip-anomalies.actions';

/** Loads the report for a window; another window (or a refresh) cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(TripAnomalyApi)) =>
    actions$.pipe(
      ofType(TripAnomaliesActions.load),
      switchMap(({ window }) =>
        api.report(window).pipe(
          map((report) => TripAnomaliesActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(TripAnomaliesActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
