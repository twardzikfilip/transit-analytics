import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as tripAnomaliesEffects from '../../store/trip-anomalies/trip-anomalies.effects';
import { tripAnomaliesFeature } from '../../store/trip-anomalies/trip-anomalies.reducer';
import { TripAnomalies } from './trip-anomalies';

/** Abnormal trips, with its store slice, loaded on first visit. */
export const TRIP_ANOMALIES_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(tripAnomaliesFeature), provideEffects(tripAnomaliesEffects)],
    component: TripAnomalies,
  },
];
