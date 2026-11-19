import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { BusLaneReport } from '../../core/models/models';
import { BusLanesActions } from './bus-lanes.actions';

export interface BusLanesState {
  /** The last report loaded; kept while another loads, so the page doesn't go blank. */
  report: BusLaneReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: BusLanesState = { report: null, loading: false, error: null };

const reducer: ActionReducer<BusLanesState> = createReducer(
  initialState,
  on(BusLanesActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(BusLanesActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(BusLanesActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const busLanesFeature = createFeature({ name: 'busLanes', reducer });
