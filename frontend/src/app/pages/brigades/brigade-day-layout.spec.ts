import { BrigadeTrip } from '../../core/models/models';
import {
  ACTUAL_Y,
  LEFT,
  MIN_PX_PER_MIN,
  hourTicks,
  timeScale,
  tripRows,
} from './brigade-day-layout';
import { TRIP_COLORS } from './brigade-format';

const at = (hhmm: string) => `2026-11-18T${hhmm}:00Z`;
const trip = (from: string, to: string, extra: Partial<BrigadeTrip> = {}): BrigadeTrip => ({
  tripId: from,
  route: '103',
  headsign: 'Choroszcz/Cmentarz',
  from: 'Piastowska',
  to: 'Choroszcz/Cmentarz',
  direction: 0,
  departure: at(from),
  arrival: at(to),
  stops: 20,
  kind: 'pending',
  note: null,
  runs: [],
  missing: [],
  ...extra,
});

describe('Brigade day layout', () => {
  const trips = [trip('06:00', '06:40'), trip('06:50', '07:30'), trip('07:40', '08:20')];

  it('spans the day with a quarter of an hour either side, never tighter than the minimum', () => {
    const wide = timeScale(trips, 2000);
    expect(wide.x(at('05:45'))).toBeCloseTo(LEFT);
    expect(wide.width).toBe(2000);
    const narrow = timeScale(trips, 100);
    expect(narrow.ppm).toBe(MIN_PX_PER_MIN);
    expect(narrow.width).toBeGreaterThan(100);
    // 06:00–08:00 UTC are 07:00–09:00 in Białystok (winter time).
    expect(hourTicks(wide).map((t) => t.label)).toEqual(['07:00', '08:00', '09:00']);
  });

  it('alternates rows and colours, and says when a trip is still to come', () => {
    const rows = tripRows(trips, timeScale(trips, 2000));
    expect(rows.map((r) => r.y)).toEqual([ACTUAL_Y[0], ACTUAL_Y[1], ACTUAL_Y[0]]);
    expect(rows.map((r) => r.color)).toEqual([TRIP_COLORS[0], TRIP_COLORS[1], TRIP_COLORS[0]]);
    expect(rows[0].busLabel).toBe('upcoming');
    expect(rows[0].noData).toEqual(rows[0].scheduled);
    expect(rows[0].scheduled.width).toBeCloseTo(rows[1].scheduled.width);
  });
});
