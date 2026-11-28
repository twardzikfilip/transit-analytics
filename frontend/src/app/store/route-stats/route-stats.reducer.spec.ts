import { RouteStatsReport } from '../../core/models/models';
import { RouteStatsActions } from './route-stats.actions';
import { routeStatsFeature } from './route-stats.reducer';

describe('line stats reducer', () => {
  const report: RouteStatsReport = {
    serviceId: 'P_1349',
    peak: [{ from: '06:00', to: '09:00' }],
    offPeak: [{ from: '17:00', to: '20:00' }],
    delayDays: 28,
    routes: [],
  };

  it('keeps the report while reloading and records failures', () => {
    let state = routeStatsFeature.reducer(undefined, RouteStatsActions.loaded({ report }));
    state = routeStatsFeature.reducer(state, RouteStatsActions.load());
    expect(state.loading).toBe(true);
    expect(state.report).toBe(report);
    state = routeStatsFeature.reducer(state, RouteStatsActions.failure({ error: 'Boom' }));
    expect(state).toEqual({ report, loading: false, error: 'Boom' });
  });
});
