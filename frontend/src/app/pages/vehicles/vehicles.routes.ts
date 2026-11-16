import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as vehiclesEffects from '../../store/vehicles/vehicles.effects';
import { vehiclesFeature } from '../../store/vehicles/vehicles.reducer';
import { Vehicles } from './vehicles';

/** Vehicles, with its store slice, loaded on first visit. */
export const VEHICLES_ROUTES: Routes = [
  { path: '', providers: [provideState(vehiclesFeature), provideEffects(vehiclesEffects)], component: Vehicles },
];
