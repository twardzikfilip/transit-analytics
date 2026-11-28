import { LivePosition } from '../../core/models/models';
import { LivePositionsActions } from './live-positions.actions';
import { livePositionsFeature } from './live-positions.reducer';

describe('live positions reducer', () => {
  const reduce = livePositionsFeature.reducer;
  const bus = (vehicleNumber: string, latitude = 53.13): LivePosition => ({
    vehicleId: 1,
    vehicleNumber,
    type: 'BUS',
    agency: 'BKM',
    model: 'Solaris Urbino 12',
    routeName: '18',
    subtype: 'suburban',
    brigade: '018-02',
    headsign: 'Warzywna',
    latitude,
    longitude: 23.16,
    bearing: 90,
    delaySeconds: 0,
    timestamp: '2026-11-16T09:00:00Z',
    upcomingStops: [],
  });

  it('replaces all positions on every successful load', () => {
    let state = reduce(
      undefined,
      LivePositionsActions.loadSuccess({ positions: [bus('230'), bus('009')], loadedAt: 't1' }),
    );
    expect(state.ids).toEqual(['230', '009']);
    expect(state.lastUpdated).toBe('t1');

    state = reduce(
      state,
      LivePositionsActions.loadSuccess({ positions: [bus('230', 53.2)], loadedAt: 't2' }),
    );
    expect(state.ids).toEqual(['230']);
    expect(state.entities['230']?.latitude).toBe(53.2);
    expect(state.lastUpdated).toBe('t2');
  });

  it('keeps the last positions when a load fails, and clears the error on the next success', () => {
    let state = reduce(
      undefined,
      LivePositionsActions.loadSuccess({ positions: [bus('230')], loadedAt: 't1' }),
    );
    state = reduce(state, LivePositionsActions.load());
    expect(state.loading).toBe(true);

    state = reduce(state, LivePositionsActions.failure({ error: 'Live data source unavailable' }));
    expect(state.ids).toEqual(['230']);
    expect(state.error).toBe('Live data source unavailable');
    expect(state.loading).toBe(false);

    state = reduce(
      state,
      LivePositionsActions.loadSuccess({ positions: [bus('230')], loadedAt: 't2' }),
    );
    expect(state.error).toBeNull();
  });
});
