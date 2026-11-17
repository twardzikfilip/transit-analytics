import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { FleetReport, VehicleDetail } from '../../core/models/models';
import { FleetActions } from './fleet.actions';

export interface FleetState {
  /** The last report loaded; kept while another window loads, so the page doesn't go blank. */
  report: FleetReport | null;
  loading: boolean;
  error: string | null;
  /** The bus chosen in the search, kept while it reloads. */
  vehicle: VehicleDetail | null;
  loadingVehicle: boolean;
  vehicleError: string | null;
}

const initialState: FleetState = {
  report: null,
  loading: false,
  error: null,
  vehicle: null,
  loadingVehicle: false,
  vehicleError: null,
};

const reducer: ActionReducer<FleetState> = createReducer(
  initialState,
  on(FleetActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(FleetActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(FleetActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  on(FleetActions.loadVehicle, (state, { id }) => ({
    ...state,
    vehicle: state.vehicle?.vehicle.id === id ? state.vehicle : null,
    loadingVehicle: true,
    vehicleError: null,
  })),
  on(FleetActions.vehicleLoaded, (state, { vehicle }) => ({ ...state, vehicle, loadingVehicle: false })),
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
