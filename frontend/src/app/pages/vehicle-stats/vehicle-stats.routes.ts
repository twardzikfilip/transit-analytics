import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as vehicleStatsEffects from '../../store/vehicle-stats/vehicle-stats.effects';
import { vehicleStatsFeature } from '../../store/vehicle-stats/vehicle-stats.reducer';
import { VehicleStats } from './vehicle-stats';

/** Vehicles in detail, with its store slice, loaded on first visit. */
export const VEHICLE_STATS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(vehicleStatsFeature), provideEffects(vehicleStatsEffects)],
    component: VehicleStats,
  },
];
