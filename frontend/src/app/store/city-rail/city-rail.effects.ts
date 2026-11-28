import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
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

export const loadComparison = createEffect(
  (actions$ = inject(Actions), api = inject(CityRailApi)) =>
    actions$.pipe(
      ofType(CityRailActions.loadComparison),
      exhaustMap(() =>
        api.comparison().pipe(
          map((comparison) => CityRailActions.comparisonLoaded({ comparison })),
          catchError((err: HttpErrorResponse) =>
            of(CityRailActions.comparisonFailure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const loadProposal = createEffect(
  (actions$ = inject(Actions), api = inject(CityRailApi)) =>
    actions$.pipe(
      ofType(CityRailActions.loadProposal),
      exhaustMap(() =>
        api.proposal().pipe(
          map((proposal) => CityRailActions.proposalLoaded({ proposal })),
          catchError((err: HttpErrorResponse) =>
            of(CityRailActions.proposalFailure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Loads the routes of a solution at a frequency; choosing another cancels the one still loading. */
export const loadVariant = createEffect(
  (actions$ = inject(Actions), api = inject(CityRailApi)) =>
    actions$.pipe(
      ofType(CityRailActions.loadVariant),
      switchMap(({ solution, frequency }) =>
        api.variant(solution, frequency).pipe(
          map((variant) => CityRailActions.variantLoaded({ solution, variant })),
          catchError((err: HttpErrorResponse) =>
            of(CityRailActions.variantFailure({ error: errorMessage(err) })),
          ),
        ),
      ),
    ),
  { functional: true },
);
