import { LineStatsReport } from '../../core/models/models';
import { LineStatsActions } from './line-stats.actions';
import { lineStatsFeature } from './line-stats.reducer';

describe('line stats reducer', () => {
  const report: LineStatsReport = {
    serviceId: 'P_1349',
    validDays: 59,
    firstDate: '2026-11-12',
    lastDate: '2027-02-05',
    peak: [{ from: '06:00', to: '09:00' }],
    offPeak: [{ from: '17:00', to: '20:00' }],
    delayDays: 28,
    lines: [],
  };

  it('keeps the report while reloading and records failures', () => {
    let state = lineStatsFeature.reducer(undefined, LineStatsActions.loaded({ report }));
    state = lineStatsFeature.reducer(state, LineStatsActions.load());
    expect(state.loading).toBe(true);
    expect(state.report).toBe(report);
    state = lineStatsFeature.reducer(state, LineStatsActions.failure({ error: 'Boom' }));
    expect(state).toEqual({ report, loading: false, error: 'Boom' });
  });
});
