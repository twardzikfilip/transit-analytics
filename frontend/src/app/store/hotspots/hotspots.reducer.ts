import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { HotspotReport } from '../../core/models/models';
import { HotspotsActions } from './hotspots.actions';

export interface HotspotsState {
  /** The last report loaded; kept while another loads, so the page doesn't go blank. */
  report: HotspotReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: HotspotsState = { report: null, loading: false, error: null };

const reducer: ActionReducer<HotspotsState> = createReducer(
  initialState,
  on(HotspotsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(HotspotsActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(HotspotsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const hotspotsFeature = createFeature({ name: 'hotspots', reducer });
