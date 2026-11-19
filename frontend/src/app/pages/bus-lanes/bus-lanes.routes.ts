import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as busLanesEffects from '../../store/bus-lanes/bus-lanes.effects';
import { busLanesFeature } from '../../store/bus-lanes/bus-lanes.reducer';
import { BusLanesPage } from './bus-lanes';

/** Bus lanes, with its store slice, loaded on first visit. */
export const BUS_LANES_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(busLanesFeature), provideEffects(busLanesEffects)],
    component: BusLanesPage,
  },
];
