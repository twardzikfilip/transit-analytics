import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as fleetEffects from '../../store/fleet/fleet.effects';
import { fleetFeature } from '../../store/fleet/fleet.reducer';
import { Fleet } from './fleet';

/** Fleet, with its store slice, loaded on first visit. */
export const FLEET_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(fleetFeature), provideEffects(fleetEffects)],
    component: Fleet,
  },
];
