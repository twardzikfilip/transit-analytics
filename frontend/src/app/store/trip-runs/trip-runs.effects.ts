import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { TripRunApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { TripRunsActions } from './trip-runs.actions';

/** Loads a page of trips; another query (or a refresh) cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(TripRunApi)) =>
    actions$.pipe(
      ofType(TripRunsActions.load),
      switchMap(({ query }) =>
        api.page(query).pipe(
          map((page) => TripRunsActions.loaded({ page })),
          catchError((err: HttpErrorResponse) =>
            of(TripRunsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
