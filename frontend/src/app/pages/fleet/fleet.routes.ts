import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as fleetEffects from '../../store/fleet/fleet.effects';
import { fleetFeature } from '../../store/fleet/fleet.reducer';
import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { FleetApi } from '../../core/api/api.services';
import { Fleet } from './fleet';
import { VehiclePage } from './vehicle';

/** "Vehicle 230" (titles.vehicle): the bus's number, looked up once per bus (not again when the window changes). */
export const vehicleTitle: ResolveFn<string> = (route) =>
  inject(FleetApi)
    .number(Number(route.paramMap.get('id')))
    .pipe(
      map((number) => `titles.vehicle:${number}`),
      catchError(() => of('titles.vehicleUnknown')),
    );

/** Fleet overview and one bus's page, sharing the store slice loaded on first visit. */
export const FLEET_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(fleetFeature), provideEffects(fleetEffects)],
    children: [
      { path: '', title: 'titles.fleetOverview', component: Fleet },
      { path: ':id', title: vehicleTitle, component: VehiclePage },
    ],
  },
];
