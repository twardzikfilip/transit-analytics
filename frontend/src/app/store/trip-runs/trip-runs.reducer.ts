import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { TripRunDay, TripRunPage } from '../../core/models/models';
import { TripRunsActions } from './trip-runs.actions';

export interface TripRunsState {
  /** The last page loaded; kept while another loads (or refreshes), so the page doesn't go blank. */
  page: TripRunPage | null;
  loading: boolean;
  /** A brigade's day (its page); kept while another loads or it refreshes. */
  day: TripRunDay | null;
  loadingDay: boolean;
  error: string | null;
}

const initialState: TripRunsState = {
  page: null,
  loading: false,
  day: null,
  loadingDay: false,
  error: null,
};

const reducer: ActionReducer<TripRunsState> = createReducer(
  initialState,
  on(TripRunsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(TripRunsActions.loaded, (state, { page }) => ({ ...state, page, loading: false })),
  on(TripRunsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  on(TripRunsActions.loadDay, (state) => ({ ...state, loadingDay: true, error: null })),
  on(TripRunsActions.dayLoaded, (state, { day }) => ({ ...state, day, loadingDay: false })),
  on(TripRunsActions.dayFailure, (state, { error }) => ({ ...state, loadingDay: false, error })),
);

export const tripRunsFeature = createFeature({ name: 'tripRuns', reducer });
