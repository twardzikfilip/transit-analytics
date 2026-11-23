import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as tripRunsEffects from '../../store/trip-runs/trip-runs.effects';
import { tripRunsFeature } from '../../store/trip-runs/trip-runs.reducer';
import { Trips } from './trips';

/** Trips, with its store slice, loaded on first visit. */
export const TRIPS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(tripRunsFeature), provideEffects(tripRunsEffects)],
    component: Trips,
  },
];
