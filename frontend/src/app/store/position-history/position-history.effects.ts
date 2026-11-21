import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { PositionHistoryApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { PositionHistoryActions } from './position-history.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(PositionHistoryActions.failure({ error: errorMessage(err) }));

/** A newer window cancels a request still in flight. */
export const loadFleet = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(PositionHistoryActions.load),
      switchMap(({ query }) =>
        api.fleet(query, query).pipe(
          map((series) => PositionHistoryActions.fleetLoaded({ series })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadDelays = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(PositionHistoryActions.load),
      switchMap(({ query }) =>
        api.delays(query, query).pipe(
          map((delays) => PositionHistoryActions.delaysLoaded({ delays })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadRoutes = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(PositionHistoryActions.load),
      switchMap(({ query }) =>
        api.routes(query, query.agency).pipe(
          map((routes) => PositionHistoryActions.routesLoaded({ routes })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadAgencies = createEffect(
  (actions$ = inject(Actions), api = inject(PositionHistoryApi)) =>
    actions$.pipe(
      ofType(PositionHistoryActions.loadAgencies),
      exhaustMap(() =>
        api.agencies().pipe(
          map((agencies) => PositionHistoryActions.agenciesLoaded({ agencies })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
