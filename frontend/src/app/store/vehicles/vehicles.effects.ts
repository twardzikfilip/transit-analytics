import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, concatMap, map, mergeMap, of, switchMap } from 'rxjs';
import { VehicleApi } from '../../core/api/api.services';
import { VehiclesActions } from './vehicles.actions';

const toFailure = (err: HttpErrorResponse) =>
  of(VehiclesActions.failure({ error: err.error?.detail ?? err.message ?? 'Request failed' }));

export const loadVehicles = createEffect(
  (actions$ = inject(Actions), api = inject(VehicleApi)) =>
    actions$.pipe(
      ofType(VehiclesActions.load),
      switchMap(() =>
        api.list(0, 500, 'vehicleNumber').pipe(
          map((page) => VehiclesActions.loadSuccess({ vehicles: page.content })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const createVehicle = createEffect(
  (actions$ = inject(Actions), api = inject(VehicleApi)) =>
    actions$.pipe(
      ofType(VehiclesActions.create),
      concatMap(({ vehicle }) =>
        api.create(vehicle).pipe(
          map((created) => VehiclesActions.createSuccess({ vehicle: created })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const updateVehicle = createEffect(
  (actions$ = inject(Actions), api = inject(VehicleApi)) =>
    actions$.pipe(
      ofType(VehiclesActions.update),
      concatMap(({ id, vehicle }) =>
        api.update(id, vehicle).pipe(
          map((updated) => VehiclesActions.updateSuccess({ vehicle: updated })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);

export const deleteVehicle = createEffect(
  (actions$ = inject(Actions), api = inject(VehicleApi)) =>
    actions$.pipe(
      ofType(VehiclesActions.delete),
      mergeMap(({ id }) =>
        api.delete(id).pipe(
          map(() => VehiclesActions.deleteSuccess({ id })),
          catchError(toFailure),
        ),
      ),
    ),
  { functional: true },
);
