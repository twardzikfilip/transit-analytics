import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as positionHistoryEffects from '../../store/position-history/position-history.effects';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { Positions } from './positions';

/** Position history, with its store slice, loaded on first visit. */
export const POSITIONS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(positionHistoryFeature), provideEffects(positionHistoryEffects)],
    component: Positions,
  },
];
