import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as stopTimesEffects from '../store/stop-times/stop-times.effects';
import { stopTimesFeature } from '../store/stop-times/stop-times.reducer';
import * as stopsEffects from '../store/stops/stops.effects';
import { stopsFeature } from '../store/stops/stops.reducer';

/**
 * Timetable pages (GTFS data). Loaded on first visit, together with their store slices, so they don't add to the
 * app's initial download.
 */
export const TIMETABLE_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(stopsFeature),
      provideState(stopTimesFeature),
      provideEffects(stopsEffects, stopTimesEffects),
    ],
    children: [
      {
        path: 'stops',
        title: 'titles.stops',
        loadComponent: () => import('./stops/stops').then((m) => m.Stops),
      },
      {
        path: 'stop-times',
        title: 'titles.stopTimes',
        loadComponent: () => import('./stop-times/stop-times').then((m) => m.StopTimes),
      },
    ],
  },
];
