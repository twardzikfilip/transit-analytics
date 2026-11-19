import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { BusLaneApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { BusLanesActions } from './bus-lanes.actions';

/** Loads the report for a window; choosing another cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(BusLaneApi)) =>
    actions$.pipe(
      ofType(BusLanesActions.load),
      switchMap(({ window }) =>
        api.report(window).pipe(
          map((report) => BusLanesActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(BusLanesActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
