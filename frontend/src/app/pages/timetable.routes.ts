import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as routesEffects from '../store/routes/routes.effects';
import { routesFeature } from '../store/routes/routes.reducer';
import * as stopTimesEffects from '../store/stop-times/stop-times.effects';
import { stopTimesFeature } from '../store/stop-times/stop-times.reducer';
import * as stopsEffects from '../store/stops/stops.effects';
import { stopsFeature } from '../store/stops/stops.reducer';
import * as tripsEffects from '../store/trips/trips.effects';
import { tripsFeature } from '../store/trips/trips.reducer';

/**
 * Timetable pages (GTFS data). Loaded on first visit, together with their store slices, so they don't add to the
 * app's initial download.
 */
export const TIMETABLE_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(routesFeature),
      provideState(stopsFeature),
      provideState(tripsFeature),
      provideState(stopTimesFeature),
      provideEffects(routesEffects, stopsEffects, tripsEffects, stopTimesEffects),
    ],
    children: [
      {
        path: 'stops',
        title: 'Stops',
        loadComponent: () => import('./stops/stops').then((m) => m.Stops),
      },
      {
        path: 'trips',
        title: 'Trips',
        loadComponent: () => import('./trips/trips').then((m) => m.Trips),
      },
      {
        path: 'stop-times',
        title: 'Stop times',
        loadComponent: () => import('./stop-times/stop-times').then((m) => m.StopTimes),
      },
    ],
  },
];
