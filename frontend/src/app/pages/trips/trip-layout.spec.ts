import { TripRun } from '../../core/models/models';
import { DELAY_STOPS } from '../../shared/palette';
import {
  axisTicks,
  delaySegments,
  dwellText,
  longestHalts,
  linePoints,
  listSpan,
  timeScale,
  tripMarks,
} from './trip-layout';
import { valueAt } from './trip-detail';

const at = (hhmm: string) => `2026-11-23T${hhmm}:00Z`;
const ms = (hhmm: string) => Date.parse(at(hhmm));

const trip = (kind: TripRun['trip']['kind']): TripRun => ({
  serviceDay: '2026-11-23',
  brigade: '103-05',
  trip: {
    tripId: 't',
    line: '103',
    headsign: null,
    from: 'A',
    to: 'B',
    direction: 0,
    departure: at('14:00'),
    arrival: at('14:20'),
    stops: 10,
    kind,
    note: null,
    runs: [
      {
        bus: '230',
        fromStop: 1,
        toStop: 6,
        fromName: 'A',
        toName: 'F',
        departure: at('14:01'),
        arrival: at('14:11'),
        startDelay: 60,
        endDelay: 600,
      },
    ],
    missing: [],
  },
  samples: [
    { at: at('13:58'), bus: '230', delay: 0, kmh: null, stop: 1 },
    { at: at('14:05'), bus: '230', delay: 30, kmh: 20, stop: 3 },
    { at: at('14:08'), bus: '230', delay: 700, kmh: 10, stop: 5 },
  ],
  stands: [],
  stops: [],
  averageKmh: null,
});

describe('Trip layout', () => {
  const scale = timeScale(ms('14:00'), ms('14:20'), 0, 200);

  it('colours the actual bar by delay from each sample on, within the run', () => {
    const segments = delaySegments(trip('lost'), scale);
    // Waiting before 14:01 isn't drawn; 14:01–14:05 at 0 s, 14:05–14:08 at 30 s, 14:08–14:11 at 700 s.
    expect(segments.map((s) => [s.x, Math.round(s.width)])).toEqual([
      [10, 40],
      [50, 30],
      [80, 30],
    ]);
    expect(segments[0].color).toBe(DELAY_STOPS[0].color);
    expect(segments[2].color).toBe(DELAY_STOPS[3].color);
  });

  it('marks where a trip ended early, and a detour', () => {
    const end = tripMarks(trip('lost'), scale).end!;
    expect(end.kind).toBe('lost');
    expect(end.x).toBeCloseTo(110);
    expect(tripMarks(trip('short-turned'), scale).end!.kind).toBe('short');
    expect(tripMarks(trip('completed'), scale).end).toBeNull();
    expect(tripMarks(trip('detour'), scale).detour).toBeCloseTo(60);
    expect(tripMarks(trip('completed'), scale).connector).toEqual({ from: 0, to: 10 });
  });

  it('spans the list from the window or the earliest trip, five minutes either side', () => {
    const [from, to] = listSpan({
      to: at('14:15'),
      shownFrom: at('14:05'),
      trips: [trip('completed')],
    });
    expect(from).toBe(ms('13:55'));
    expect(to).toBe(ms('14:25'));
  });

  it('breaks a line where values are missing, the bus changes, or nothing came for two minutes', () => {
    const samples = [
      { at: at('14:00'), bus: '1', delay: 0, kmh: null, stop: 1 },
      { at: at('14:01'), bus: '1', delay: 0, kmh: 10, stop: 1 },
      { at: at('14:04'), bus: '1', delay: 0, kmh: 20, stop: 2 },
      { at: at('14:05'), bus: '2', delay: 0, kmh: 30, stop: 2 },
    ];
    expect(linePoints(samples, (p) => p.kmh).map((l) => l.length)).toEqual([1, 1, 1]);
    expect(linePoints(samples, (p) => p.delay).map((l) => l.length)).toEqual([2, 1, 1]);
  });

  it('puts ticks at round times, as far apart as the width allows', () => {
    const ticks = axisTicks(timeScale(ms('14:00'), ms('16:00'), 0, 600));
    expect(ticks.map((t) => Math.round(t.x))).toEqual([0, 75, 150, 225, 300, 375, 450, 525, 600]);
  });

  it("reads a drawn line's value at a moment, nothing where it isn't drawn", () => {
    const lines = [
      [
        { time: 0, value: 0 },
        { time: 60, value: 6 },
      ],
      [{ time: 200, value: 3 }],
    ];
    expect([0, 30, 60, 100, 200, 300].map((time) => valueAt(lines, time))).toEqual([
      0,
      3,
      6,
      null,
      3,
      null,
    ]);
  });

  it('says how long the bus stood at a stop, and finds the longest halts', () => {
    expect([null, 0, 35, 80].map(dwellText)).toEqual([
      '',
      "Didn't stop",
      'Stood 0:35 at the stop',
      'Stood 1:20 at the stop',
    ]);
    const stop = (index: number, dwellSeconds: number | null) => ({
      index,
      name: `S${index}`,
      lat: 0,
      lon: 0,
      dwellSeconds,
    });
    expect(
      longestHalts([stop(1, 30), stop(2, 10), stop(3, null), stop(4, 90), stop(5, 30)], 2).map(
        (s) => s.name,
      ),
    ).toEqual(['S4', 'S1']);
  });
});
