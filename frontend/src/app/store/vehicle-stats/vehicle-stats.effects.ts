import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { VehicleStatsApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { VehicleStatsActions } from './vehicle-stats.actions';

/** Loads the report for a window and operators; choosing others cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(VehicleStatsApi)) =>
    actions$.pipe(
      ofType(VehicleStatsActions.load),
      switchMap(({ window, agencies }) =>
        api.report(window, agencies).pipe(
          map((report) => VehicleStatsActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(VehicleStatsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
