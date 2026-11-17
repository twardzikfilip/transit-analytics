import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { TripAnomalyReport } from '../../core/models/models';
import { TripAnomaliesActions } from './trip-anomalies.actions';

export interface TripAnomaliesState {
  /** The last report loaded; kept while another loads (or refreshes), so the page doesn't go blank. */
  report: TripAnomalyReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: TripAnomaliesState = { report: null, loading: false, error: null };

const reducer: ActionReducer<TripAnomaliesState> = createReducer(
  initialState,
  on(TripAnomaliesActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(TripAnomaliesActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(TripAnomaliesActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const tripAnomaliesFeature = createFeature({ name: 'tripAnomalies', reducer });
