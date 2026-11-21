import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as lineStatsEffects from '../../store/line-stats/line-stats.effects';
import { lineStatsFeature } from '../../store/line-stats/line-stats.reducer';
import * as routeDetailEffects from '../../store/route-detail/route-detail.effects';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { RoutesPage } from './routes';

/** Routes, with its store slices (the lines, and every line's weekday statistics), loaded on first visit. */
export const ROUTES_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(routeDetailFeature),
      provideEffects(routeDetailEffects),
      provideState(lineStatsFeature),
      provideEffects(lineStatsEffects),
    ],
    component: RoutesPage,
  },
];
