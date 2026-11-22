import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { FleetReport, VehicleDetail } from '../../core/models/models';
import { FleetActions } from './fleet.actions';

export interface FleetState {
  /** The last report loaded; kept while another window loads, so the page doesn't go blank. */
  report: FleetReport | null;
  /** The query the report (or the one loading) answers, and when it arrived (ms). */
  reportKey: string | null;
  reportAt: number | null;
  loading: boolean;
  error: string | null;
  /** The bus chosen in the search, kept while it reloads. */
  vehicle: VehicleDetail | null;
  loadingVehicle: boolean;
  vehicleError: string | null;
}

const initialState: FleetState = {
  report: null,
  reportKey: null,
  reportAt: null,
  loading: false,
  error: null,
  vehicle: null,
  loadingVehicle: false,
  vehicleError: null,
};

const reducer: ActionReducer<FleetState> = createReducer(
  initialState,
  on(FleetActions.load, (state, { key }) => ({
    ...state,
    reportKey: key ?? null,
    reportAt: null,
    loading: true,
    error: null,
  })),
  on(FleetActions.loaded, (state, { report }) => ({
    ...state,
    report,
    reportAt: Date.now(),
    loading: false,
  })),
  on(FleetActions.failure, (state, { error }) => ({
    ...state,
    reportKey: null,
    loading: false,
    error,
  })),
  on(FleetActions.loadVehicle, (state, { id }) => ({
    ...state,
    vehicle: state.vehicle?.vehicle.id === id ? state.vehicle : null,
    loadingVehicle: true,
    vehicleError: null,
  })),
  on(FleetActions.vehicleLoaded, (state, { vehicle }) => ({
    ...state,
    vehicle,
    loadingVehicle: false,
  })),
  on(FleetActions.vehicleFailure, (state, { error }) => ({
    ...state,
    loadingVehicle: false,
    vehicleError: error,
  })),
  on(FleetActions.clearVehicle, (state) => ({
    ...state,
    vehicle: null,
    loadingVehicle: false,
    vehicleError: null,
  })),
);

export const fleetFeature = createFeature({ name: 'fleet', reducer });

/** A report loaded (or loading) for the same query less than this long ago is used again, not requested anew. */
export const REPORT_FRESH_MS = 5 * 60_000;

/** Whether the fleet report for `key` is in the store, or on its way, and fresh enough to use again. */
export function hasFreshReport(
  state: FleetState | undefined,
  key: string,
  now = Date.now(),
): boolean {
  if (!state || state.reportKey !== key) return false;
  return state.loading || (state.reportAt !== null && now - state.reportAt < REPORT_FRESH_MS);
}
