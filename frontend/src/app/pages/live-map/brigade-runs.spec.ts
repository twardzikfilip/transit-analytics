import { ScheduledRun } from '../../core/models/models';
import { remainingRuns } from './brigade-runs';

describe('remaining brigade runs', () => {
  const run = (departure: string, arrival: string): ScheduledRun => ({
    line: '103',
    headsign: null,
    from: 'A',
    to: 'B',
    departure: `2026-11-16T${departure}:00Z`,
    arrival: `2026-11-16T${arrival}:00Z`,
  });

  it('leaves out finished runs and marks the one under way', () => {
    const runs = [run('06:00', '06:40'), run('07:00', '07:40'), run('08:00', '08:40')];
    expect(remainingRuns(runs, new Date('2026-11-16T07:10:00Z'))).toEqual([
      { run: runs[1], now: true },
      { run: runs[2], now: false },
    ]);
    expect(remainingRuns(runs, new Date('2026-11-16T07:50:00Z'))).toEqual([
      { run: runs[2], now: false },
    ]);
    expect(remainingRuns(runs, new Date('2026-11-16T09:00:00Z'))).toEqual([]);
  });
});
