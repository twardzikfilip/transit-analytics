import { createFeature, createReducer, on } from '@ngrx/store';
import { LineStatsReport } from '../../core/models/models';
import { LineStatsActions } from './line-stats.actions';

export interface LineStatsState {
  /** Weekday statistics for every line; kept while reloading. */
  report: LineStatsReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: LineStatsState = { report: null, loading: false, error: null };

export const lineStatsFeature = createFeature({
  name: 'lineStats',
  reducer: createReducer(
    initialState,
    on(LineStatsActions.load, (state) => ({ ...state, loading: true, error: null })),
    on(LineStatsActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
    on(LineStatsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
});
