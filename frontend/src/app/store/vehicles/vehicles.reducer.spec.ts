import { VehiclesActions } from './vehicles.actions';
import { vehiclesFeature } from './vehicles.reducer';

describe('vehicles reducer', () => {
  const reduce = vehiclesFeature.reducer;
  const bus = { id: 1, vehicleNumber: 'BUS-1', type: 'BUS', capacity: 50, active: true };

  it('loads, updates and deletes vehicles', () => {
    let state = reduce(undefined, VehiclesActions.loadSuccess({ vehicles: [bus] }));
    expect(state.ids).toEqual([1]);

    state = reduce(state, VehiclesActions.updateSuccess({ vehicle: { ...bus, active: false } }));
    expect(state.entities[1]?.active).toBe(false);

    state = reduce(state, VehiclesActions.deleteSuccess({ id: 1 }));
    expect(state.ids).toEqual([]);
  });
});
