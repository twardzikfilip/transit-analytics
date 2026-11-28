import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { HotspotApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { HotspotsActions } from './hotspots.actions';

/** Loads the report for a window and route; choosing others cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(HotspotApi)) =>
    actions$.pipe(
      ofType(HotspotsActions.load),
      switchMap(({ window, route }) =>
        api.report(window, route).pipe(
          map((report) => HotspotsActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(HotspotsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
