import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap } from 'rxjs';
import { FleetApi } from '../../core/api/api.services';
import { errorMessage } from '../paged';
import { FleetActions } from './fleet.actions';

/** Loads the fleet for a window and operators; choosing others cancels the one still loading. */
export const load = createEffect(
  (actions$ = inject(Actions), api = inject(FleetApi)) =>
    actions$.pipe(
      ofType(FleetActions.load),
      switchMap(({ window, agencies }) =>
        api.report(window, agencies).pipe(
          map((report) => FleetActions.loaded({ report })),
          catchError((err: HttpErrorResponse) => of(FleetActions.failure({ error: errorMessage(err) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Loads one bus; choosing another (or clearing it) cancels the one still loading. */
export const loadVehicle = createEffect(
  (actions$ = inject(Actions), api = inject(FleetApi)) =>
    actions$.pipe(
      ofType(FleetActions.loadVehicle, FleetActions.clearVehicle),
      switchMap((action) =>
        action.type === FleetActions.clearVehicle.type
          ? of()
          : api.vehicle(action.id, action.window).pipe(
              map((vehicle) => FleetActions.vehicleLoaded({ vehicle })),
              catchError((err: HttpErrorResponse) =>
                of(FleetActions.vehicleFailure({ error: errorMessage(err) })),
              ),
            ),
      ),
    ),
  { functional: true },
);
