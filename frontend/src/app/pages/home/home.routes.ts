import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as livePositionsEffects from '../../store/live-positions/live-positions.effects';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import * as positionHistoryEffects from '../../store/position-history/position-history.effects';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { Home } from './home';

/** Home (the live map and the recorded positions), with its store slices, loaded on first visit. */
export const HOME_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(livePositionsFeature),
      provideState(positionHistoryFeature),
      provideEffects(livePositionsEffects, positionHistoryEffects),
    ],
    component: Home,
  },
];
