import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { TripRunPage } from '../../core/models/models';
import { TripRunsActions } from './trip-runs.actions';

export interface TripRunsState {
  /** The last page loaded; kept while another loads (or refreshes), so the page doesn't go blank. */
  page: TripRunPage | null;
  loading: boolean;
  error: string | null;
}

const initialState: TripRunsState = { page: null, loading: false, error: null };

const reducer: ActionReducer<TripRunsState> = createReducer(
  initialState,
  on(TripRunsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(TripRunsActions.loaded, (state, { page }) => ({ ...state, page, loading: false })),
  on(TripRunsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const tripRunsFeature = createFeature({ name: 'tripRuns', reducer });
