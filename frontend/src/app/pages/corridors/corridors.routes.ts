import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as corridorsEffects from '../../store/corridors/corridors.effects';
import { corridorsFeature } from '../../store/corridors/corridors.reducer';
import { Corridors } from './corridors';

/** Important corridors, with its store slice, loaded on first visit. */
export const CORRIDORS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(corridorsFeature), provideEffects(corridorsEffects)],
    component: Corridors,
  },
];
