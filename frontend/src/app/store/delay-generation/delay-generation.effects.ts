import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { DelayGenerationApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { DelayGenerationActions } from './delay-generation.actions';

/** Loads the network for a window; choosing another window cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(DelayGenerationApi)) =>
    actions$.pipe(
      ofType(DelayGenerationActions.load),
      switchMap(({ window }) =>
        api.report(window).pipe(
          map((report) => DelayGenerationActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(DelayGenerationActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Loads a route; choosing another route (or clearing it) cancels the one still loading. */
export const loadRoute = createEffect(
  (actions$ = inject(Actions), api = inject(DelayGenerationApi)) =>
    actions$.pipe(
      ofType(DelayGenerationActions.loadRoute, DelayGenerationActions.clearRoute),
      switchMap((action) =>
        action.type === DelayGenerationActions.clearRoute.type
          ? of()
          : api.route(action.window, action.route).pipe(
              map((report) => DelayGenerationActions.routeLoaded({ report })),
              catchError((err: HttpErrorResponse) =>
                of(DelayGenerationActions.routeFailure({ error: errorMessage(err) })),
              ),
            ),
      ),
    ),
  { functional: true },
);
