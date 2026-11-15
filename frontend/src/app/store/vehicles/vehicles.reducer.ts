import { createEntityAdapter, EntityState } from '@ngrx/entity';
import { createFeature, createReducer, on } from '@ngrx/store';
import { Vehicle } from '../../core/models/models';
import { VehiclesActions } from './vehicles.actions';

export interface VehiclesState extends EntityState<Vehicle> {
  loading: boolean;
  error: string | null;
}

const adapter = createEntityAdapter<Vehicle>({
  sortComparer: (a, b) => a.vehicleNumber.localeCompare(b.vehicleNumber),
});

const initialState: VehiclesState = adapter.getInitialState({ loading: false, error: null });

export const vehiclesFeature = createFeature({
  name: 'vehicles',
  reducer: createReducer(
    initialState,
    on(VehiclesActions.load, VehiclesActions.create, VehiclesActions.update, VehiclesActions.delete, (state) => ({
      ...state,
      loading: true,
      error: null,
    })),
    on(VehiclesActions.loadSuccess, (state, { vehicles }) => adapter.setAll(vehicles, { ...state, loading: false })),
    on(VehiclesActions.createSuccess, (state, { vehicle }) => adapter.addOne(vehicle, { ...state, loading: false })),
    on(VehiclesActions.updateSuccess, (state, { vehicle }) => adapter.upsertOne(vehicle, { ...state, loading: false })),
    on(VehiclesActions.deleteSuccess, (state, { id }) => adapter.removeOne(id, { ...state, loading: false })),
    on(VehiclesActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
  extraSelectors: ({ selectVehiclesState }) => adapter.getSelectors(selectVehiclesState),
});
