import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { LineDetailApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RouteDetailActions } from './route-detail.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(RouteDetailActions.failure({ error: errorMessage(err) }));

export const loadLines = createEffect(
  (actions$ = inject(Actions), api = inject(LineDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.loadLines),
      exhaustMap(() =>
        api.lines().pipe(
          map((lines) => RouteDetailActions.linesLoaded({ lines })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

/** Picking another line cancels the previous line's requests. */
export const loadDetail = createEffect(
  (actions$ = inject(Actions), api = inject(LineDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.select),
      switchMap(({ line }) =>
        api.detail(line).pipe(
          map((detail) => RouteDetailActions.detailLoaded({ detail })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const loadPerformance = createEffect(
  (actions$ = inject(Actions), api = inject(LineDetailApi)) =>
    actions$.pipe(
      ofType(RouteDetailActions.select),
      switchMap(({ line }) =>
        api.performance(line).pipe(
          map((performance) => RouteDetailActions.performanceLoaded({ line, performance })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
