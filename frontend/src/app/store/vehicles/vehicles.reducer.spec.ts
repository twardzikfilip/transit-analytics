import { VehiclesActions } from './vehicles.actions';
import { vehiclesFeature } from './vehicles.reducer';

describe('vehicles reducer', () => {
  const reduce = vehiclesFeature.reducer;
  const bus = { id: 1, vehicleNumber: 'BUS-1', type: 'BUS', capacity: 50, agency: null, model: null };

  it('loads, updates and deletes vehicles', () => {
    let state = reduce(undefined, VehiclesActions.loadSuccess({ vehicles: [bus] }));
    expect(state.ids).toEqual([1]);

    state = reduce(state, VehiclesActions.updateSuccess({ vehicle: { ...bus, capacity: 60 } }));
    expect(state.entities[1]?.capacity).toBe(60);

    state = reduce(state, VehiclesActions.deleteSuccess({ id: 1 }));
    expect(state.ids).toEqual([]);
  });

  it('adds a created vehicle, and keeps an error until the next request', () => {
    let state = reduce(undefined, VehiclesActions.create({ vehicle: { ...bus, id: undefined } as never }));
    expect(state.loading).toBe(true);
    state = reduce(state, VehiclesActions.createSuccess({ vehicle: bus }));
    expect(state.ids).toEqual([1]);
    expect(state.loading).toBe(false);

    state = reduce(state, VehiclesActions.failure({ error: 'Vehicle number already exists' }));
    expect(state.error).toBe('Vehicle number already exists');
    state = reduce(state, VehiclesActions.load());
    expect(state.error).toBeNull();
  });
});
