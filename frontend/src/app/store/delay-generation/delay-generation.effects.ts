import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { DelayGenerationApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { DelayGenerationActions } from './delay-generation.actions';

/** Loads the network for a window; choosing another window cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(DelayGenerationApi)) =>
    actions$.pipe(
      ofType(DelayGenerationActions.load),
      switchMap(({ window }) =>
        api.report(window).pipe(
          map((report) => DelayGenerationActions.loaded({ report })),
          catchError((err: HttpErrorResponse) =>
            of(DelayGenerationActions.failure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Loads a line; choosing another line (or clearing it) cancels the one still loading. */
export const loadLine = createEffect(
  (actions$ = inject(Actions), api = inject(DelayGenerationApi)) =>
    actions$.pipe(
      ofType(DelayGenerationActions.loadLine, DelayGenerationActions.clearLine),
      switchMap((action) =>
        action.type === DelayGenerationActions.clearLine.type
          ? of()
          : api.line(action.window, action.line).pipe(
              map((report) => DelayGenerationActions.lineLoaded({ report })),
              catchError((err: HttpErrorResponse) =>
                of(DelayGenerationActions.lineFailure({ error: errorMessage(err) })),
              ),
            ),
      ),
    ),
  { functional: true },
);
