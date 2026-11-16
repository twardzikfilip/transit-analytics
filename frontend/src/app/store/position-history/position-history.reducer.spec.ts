import { FleetSeries } from '../../core/models/models';
import { PositionHistoryActions } from './position-history.actions';
import { positionHistoryFeature } from './position-history.reducer';

describe('position history reducer', () => {
  const reduce = positionHistoryFeature.reducer;
  const query = { from: '2026-11-16T10:00:00Z', to: '2026-11-16T11:00:00Z', route: '103' };
  const series: FleetSeries = {
    from: query.from,
    to: query.to,
    stepSeconds: 60,
    route: '103',
    points: [{ time: query.from, vehicles: 7, peakVehicles: 7, samples: 7, meanDelaySeconds: 30 }],
    summary: {
      samples: 7,
      vehicles: 8,
      peakVehicles: 7,
      latestVehicles: 7,
      latestPoll: query.from,
      meanDelaySeconds: 30,
      first: query.from,
      last: query.from,
    },
  };

  it('keeps the previous series while a new window loads, then replaces it', () => {
    let state = reduce(undefined, PositionHistoryActions.load({ query }));
    state = reduce(state, PositionHistoryActions.fleetLoaded({ series }));
    expect(state.loadingSeries).toBe(false);

    state = reduce(state, PositionHistoryActions.load({ query: { ...query, route: null } }));
    expect(state.loadingSeries).toBe(true);
    expect(state.series).toBe(series);
    expect(state.query?.route).toBeNull();
  });

  it('stores lines and samples, and stops loading on failure', () => {
    let state = reduce(undefined, PositionHistoryActions.routesLoaded({ routes: [{ route: '2', samples: 10 }] }));
    expect(state.routes).toHaveLength(1);
    state = reduce(state, PositionHistoryActions.delaysLoaded({ delays: [{ fromMinutes: 1, toMinutes: 2, vehicles: 3, reports: 9 }] }));
    expect(state.delays[0].vehicles).toBe(3);
    state = reduce(state, PositionHistoryActions.loadSamples({ query, page: 0 }));
    expect(state.loadingSamples).toBe(true);
    state = reduce(state, PositionHistoryActions.failure({ error: 'The time window can be at most 31 days' }));
    expect(state.loadingSamples).toBe(false);
    expect(state.error).toBe('The time window can be at most 31 days');
  });
});
