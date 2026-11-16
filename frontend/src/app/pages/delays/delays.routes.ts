import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as delayReplayEffects from '../../store/delay-replay/delay-replay.effects';
import { delayReplayFeature } from '../../store/delay-replay/delay-replay.reducer';
import { Delays } from './delays';

/** Delays (animated replay), with its store slice, loaded on first visit. */
export const DELAYS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(delayReplayFeature), provideEffects(delayReplayEffects)],
    component: Delays,
  },
];
