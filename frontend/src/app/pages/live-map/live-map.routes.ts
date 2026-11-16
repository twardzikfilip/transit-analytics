import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as livePositionsEffects from '../../store/live-positions/live-positions.effects';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import { LiveMap } from './live-map';

/** Live map, with its store slice, loaded on first visit. */
export const LIVE_MAP_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(livePositionsFeature), provideEffects(livePositionsEffects)],
    component: LiveMap,
  },
];
