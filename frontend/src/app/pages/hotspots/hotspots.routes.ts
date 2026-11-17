import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as hotspotsEffects from '../../store/hotspots/hotspots.effects';
import { hotspotsFeature } from '../../store/hotspots/hotspots.reducer';
import { Hotspots } from './hotspots';

/** Stop hotspots, with its store slice, loaded on first visit. */
export const HOTSPOTS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(hotspotsFeature), provideEffects(hotspotsEffects)],
    component: Hotspots,
  },
];
