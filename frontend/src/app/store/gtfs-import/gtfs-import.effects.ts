import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { GtfsApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RoutesActions } from '../routes/routes.actions';
import { GtfsImportActions } from './gtfs-import.actions';

export const importBialystok = createEffect(
  (actions$ = inject(Actions), api = inject(GtfsApi)) =>
    actions$.pipe(
      ofType(GtfsImportActions.importBialystok),
      // Ignore extra clicks while an import is running.
      exhaustMap(() =>
        api.importBialystok().pipe(
          map((result) => GtfsImportActions.importSuccess({ result })),
          catchError((err: HttpErrorResponse) => of(GtfsImportActions.importFailure({ error: errorMessage(err) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Routes are kept in the store for pickers; refresh them after a new timetable arrives. */
export const reloadRoutesAfterImport = createEffect(
  (actions$ = inject(Actions)) =>
    actions$.pipe(
      ofType(GtfsImportActions.importSuccess),
      map(() => RoutesActions.load()),
    ),
  { functional: true },
);
