import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { BrigadeApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { BrigadesActions } from './brigades.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(BrigadesActions.failure({ error: errorMessage(err) }));

/** Loads the brigades of a day and agency; choosing others cancels the one still loading. */
export const loadList = createEffect(
  (actions$ = inject(Actions), api = inject(BrigadeApi)) =>
    actions$.pipe(
      ofType(BrigadesActions.loadList),
      switchMap(({ date, agency }) =>
        api.list(date, agency).pipe(
          map((list) => BrigadesActions.listLoaded({ list })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

/** Loads a brigade's day; choosing another cancels the one still loading. */
export const loadDay = createEffect(
  (actions$ = inject(Actions), api = inject(BrigadeApi)) =>
    actions$.pipe(
      ofType(BrigadesActions.loadDay),
      switchMap(({ brigade, date }) =>
        api.day(brigade, date).pipe(
          map((day) => BrigadesActions.dayLoaded({ day })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
