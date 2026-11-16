import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { CorridorApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { CorridorsActions } from './corridors.actions';

export const load = createEffect(
  (actions$ = inject(Actions), api = inject(CorridorApi)) =>
    actions$.pipe(
      ofType(CorridorsActions.load),
      exhaustMap(() =>
        api.report().pipe(
          map((report) => CorridorsActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(CorridorsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
