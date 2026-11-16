import { CorridorReport } from '../../core/models/models';
import { CorridorsActions } from './corridors.actions';
import { corridorsFeature } from './corridors.reducer';

describe('corridors reducer', () => {
  it('keeps the previous report while reloading and clears the error', () => {
    const report = { serviceId: 'P', corridors: [] } as unknown as CorridorReport;
    let state = corridorsFeature.reducer(undefined, CorridorsActions.failure({ error: 'boom' }));
    state = corridorsFeature.reducer(state, CorridorsActions.load());
    expect(state.loading).toBe(true);
    expect(state.error).toBeNull();
    state = corridorsFeature.reducer(state, CorridorsActions.loaded({ report }));
    state = corridorsFeature.reducer(state, CorridorsActions.load());
    expect(state.report).toBe(report);
  });
});
