import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { PositionHistoryApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { DelayReplayActions } from './delay-replay.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(DelayReplayActions.failure({ error: errorMessage(err) }));

/** A newer query cancels a replay still loading (a day of frames can take a moment). */
export const loadReplay = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(DelayReplayActions.load),
      switchMap(({ query }) =>
        api.replay(query.from, query.to, query.routes).pipe(
          map((replay) => DelayReplayActions.replayLoaded({ replay })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadRoutes = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(DelayReplayActions.load),
      switchMap(({ query }) =>
        api.routes(query.from, query.to).pipe(
          map((routes) => DelayReplayActions.routesLoaded({ routes })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
