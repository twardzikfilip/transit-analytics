import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { RouteDetailApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RouteDetailActions } from './route-detail.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(RouteDetailActions.failure({ error: errorMessage(err) }));

export const loadRoutes = createEffect(
  (actions$ = inject(Actions), api = inject(RouteDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.loadRoutes),
      exhaustMap(() =>
        api.routes().pipe(
          map((routes) => RouteDetailActions.routesLoaded({ routes })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

/** Picking another route cancels the previous route's requests. */
export const loadDetail = createEffect(
  (actions$ = inject(Actions), api = inject(RouteDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.select),
      switchMap(({ route }) =>
        api.detail(route).pipe(
          map((detail) => RouteDetailActions.detailLoaded({ detail })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadPerformance = createEffect(
  (actions$ = inject(Actions), api = inject(RouteDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.select),
      switchMap(({ route }) =>
        api.performance(route).pipe(
          map((performance) => RouteDetailActions.performanceLoaded({ route, performance })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
