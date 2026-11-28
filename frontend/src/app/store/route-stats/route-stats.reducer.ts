import { createFeature, createReducer, on } from '@ngrx/store';
import { RouteStatsReport } from '../../core/models/models';
import { RouteStatsActions } from './route-stats.actions';

export interface RouteStatsState {
  /** Weekday statistics for every route; kept while reloading. */
  report: RouteStatsReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: RouteStatsState = { report: null, loading: false, error: null };

export const routeStatsFeature = createFeature({
  name: 'lineStats',
  reducer: createReducer(
    initialState,
    on(RouteStatsActions.load, (state) => ({ ...state, loading: true, error: null })),
    on(RouteStatsActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
    on(RouteStatsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
});
