import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as lineStatsEffects from '../../store/line-stats/line-stats.effects';
import { lineStatsFeature } from '../../store/line-stats/line-stats.reducer';
import { LineStatsPage } from './line-stats';

/** Line statistics, with its store slice, loaded on first visit. */
export const LINE_STATS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(lineStatsFeature), provideEffects(lineStatsEffects)],
    component: LineStatsPage,
  },
];
