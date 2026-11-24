import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { VersionApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { VersionsActions } from './versions.actions';

export const load = createEffect(
  (actions$ = inject(Actions), api = inject(VersionApi)) =>
    actions$.pipe(
      ofType(VersionsActions.load),
      exhaustMap(() =>
        api.versions().pipe(
          map((versions) => VersionsActions.loaded({ versions })),
          catchError((err: HttpErrorResponse) =>
            of(VersionsActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
