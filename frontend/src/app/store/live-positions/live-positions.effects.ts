import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { ZbiorkomApi } from '../../core/api/api.services';
import { LivePositionsActions } from './live-positions.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(LivePositionsActions.failure({ error: err.error?.detail ?? err.message ?? 'Request failed' }));

export const loadLivePositions = createEffect(
  (actions$ = inject(Actions), api = inject(ZbiorkomApi)) =>
    actions$.pipe(
      ofType(LivePositionsActions.load),
      // A new poll cancels one still in flight.
      switchMap(() =>
        api.bialystokPositions().pipe(
          map((positions) => LivePositionsActions.loadSuccess({ positions, loadedAt: new Date().toISOString() })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
