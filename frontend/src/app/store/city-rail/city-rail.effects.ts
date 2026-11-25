import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { CityRailApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { CityRailActions } from './city-rail.actions';

export const load = createEffect(
  (actions$ = inject(Actions), api = inject(CityRailApi)) =>
    actions$.pipe(
      ofType(CityRailActions.load),
      exhaustMap(() =>
        api.report().pipe(
          map((report) => CityRailActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(CityRailActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
