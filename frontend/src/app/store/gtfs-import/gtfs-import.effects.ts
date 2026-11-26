import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { GtfsApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { RoutesActions } from '../routes/routes.actions';
import { GtfsImportActions } from './gtfs-import.actions';

export const loadFeeds = createEffect(
  (actions$ = inject(Actions), api = inject(GtfsApi)) =>
    actions$.pipe(
      ofType(GtfsImportActions.loadFeeds),
      switchMap(() =>
        api.feeds().pipe(
          map((feeds) => GtfsImportActions.feedsLoaded({ feeds })),
          catchError((err: HttpErrorResponse) =>
            of(GtfsImportActions.feedsFailure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const importFeed = createEffect(
  (actions$ = inject(Actions), api = inject(GtfsApi)) =>
    actions$.pipe(
      ofType(GtfsImportActions.importFeed),
      // One import at a time: ignore clicks while one is running.
      exhaustMap(({ feed }) =>
        api.importFeed(feed).pipe(
          map((result) => GtfsImportActions.importSuccess({ result })),
          catchError((err: HttpErrorResponse) =>
            of(GtfsImportActions.importFailure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** After an import, the feeds' last imports change, and the routes kept for pickers may too. */
export const reloadAfterImport = createEffect(
  (actions$ = inject(Actions)) =>
    actions$.pipe(
      ofType(GtfsImportActions.importSuccess),
      switchMap(({ result }) =>
        result.unchanged
          ? of(GtfsImportActions.loadFeeds())
          : of(GtfsImportActions.loadFeeds(), RoutesActions.load()),
      ),
    ),
  { functional: true },
);
