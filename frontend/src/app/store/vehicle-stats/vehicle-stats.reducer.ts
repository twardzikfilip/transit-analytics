import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { VehicleStatsReport } from '../../core/models/models';
import { VehicleStatsActions } from './vehicle-stats.actions';

export interface VehicleStatsState {
  /** The last report loaded; kept while another window loads, so the page doesn't go blank. */
  report: VehicleStatsReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: VehicleStatsState = { report: null, loading: false, error: null };

const reducer: ActionReducer<VehicleStatsState> = createReducer(
  initialState,
  on(VehicleStatsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(VehicleStatsActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(VehicleStatsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const vehicleStatsFeature = createFeature({ name: 'vehicleStats', reducer });
