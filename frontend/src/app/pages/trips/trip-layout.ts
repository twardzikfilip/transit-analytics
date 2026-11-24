import { TripDetailStop, TripRun, TripRunPage } from '../../core/models/models';
import { formatDelay, localTime } from '../../shared/format/time';
import { delayColor } from '../../shared/palette';
import { departureDelayText } from '../brigades/brigade-format';
import { t } from '../../core/i18n/t';

/**
 * Where everything goes on the Trips page's time lines: pure functions of the trips and the width there is, so they
 * can be tested without drawing.
 */

const MINUTE = 60_000;

/** A time axis: the span drawn, its pixel range, and where a time falls. */
export interface TimeScale {
  from: number;
  to: number;
  left: number;
  right: number;
  x(time: string | number): number;
}

export function timeScale(from: number, to: number, left: number, right: number): TimeScale {
  const span = to - from || 1;
  return {
    from,
    to,
    left,
    right,
    x: (time) =>
      left +
      (((typeof time === 'number' ? time : Date.parse(time)) - from) / span) * (right - left),
  };
}

/**
 * The span the list's shared axis covers: the window listed, widened to every trip's scheduled and actual times
 * (a trip in the window may start before it or end after it), with five minutes either side.
 */
export function listSpan(page: Pick<TripRunPage, 'trips' | 'shownFrom' | 'to'>): [number, number] {
  const times = page.trips.flatMap((r) => tripTimes(r));
  const from = Math.min(Date.parse(page.shownFrom), ...times) - 5 * MINUTE;
  const to = Math.max(Date.parse(page.to), ...times) + 5 * MINUTE;
  return [from, to];
}

/** A trip's scheduled and actual start and end (epoch ms). */
export function tripTimes(r: TripRun): number[] {
  return [
    Date.parse(r.trip.departure),
    Date.parse(r.trip.arrival),
    ...r.trip.runs.flatMap((run) => [Date.parse(run.departure), Date.parse(run.arrival)]),
  ];
}

export interface Bar {
  x: number;
  width: number;
}

function bar(s: TimeScale, from: string | number, to: string | number): Bar {
  const x = s.x(from);
  return { x, width: Math.max(2, s.x(to) - x) };
}

/** How the trip ended, when it didn't run to its last stop: turned short, or the bus was lost. */
export type EndMark = 'short' | 'lost';

/**
 * One trip on a time line: the scheduled bar; what ran, a bar per bus coloured by its delay along the way; parts not
 * run (or not seen); where a bus stood still; a change of bus; and where the trip ended early.
 */
export function tripMarks(r: TripRun, s: TimeScale) {
  const trip = r.trip;
  const last = trip.runs[trip.runs.length - 1];
  const end: EndMark | null =
    trip.kind === 'short-turned' ? 'short' : trip.kind === 'lost' ? 'lost' : null;
  return {
    scheduled: bar(s, trip.departure, trip.arrival),
    runs: trip.runs.map((run) => ({ ...bar(s, run.departure, run.arrival), bus: run.bus })),
    delays: delaySegments(r, s),
    missing: trip.missing.map((m) => ({ ...bar(s, m.from, m.to), why: m.why })),
    stands: r.stands.map((st) => ({ ...bar(s, st.from, st.to), stand: st })),
    changes: trip.runs.slice(1).map((run) => s.x(run.departure)),
    end: end && last ? { x: s.x(last.arrival), kind: end } : null,
    detour:
      trip.kind === 'detour' && last ? (s.x(trip.runs[0].departure) + s.x(last.arrival)) / 2 : null,
    connector: trip.runs[0] ? { from: s.x(trip.departure), to: s.x(trip.runs[0].departure) } : null,
  };
}

/**
 * The actual bars in colour: from each sample to the bus's next one, in the colour of its delay then, within the
 * bus's run (waiting at the first stop before it sets off isn't drawn).
 */
export function delaySegments(r: TripRun, s: TimeScale): (Bar & { color: string })[] {
  const segments: (Bar & { color: string })[] = [];
  for (const run of r.trip.runs) {
    const from = Date.parse(run.departure);
    const to = Date.parse(run.arrival);
    const own = r.samples.filter((p) => p.bus === run.bus);
    own.forEach((p, i) => {
      const start = Math.max(from, Date.parse(p.at));
      const next = own[i + 1];
      const end = Math.min(to, next ? Date.parse(next.at) : to);
      if (end > start && p.delay !== null) {
        segments.push({ ...bar(s, start, end), color: delayColor(p.delay) });
      }
    });
  }
  return segments;
}

/** Hour (or longer) ticks for an axis, at most one per `minPx`. */
export function axisTicks(s: TimeScale, minPx = 70): { x: number; label: string }[] {
  const steps = [5, 10, 15, 30, 60, 120, 180, 360, 720].map((m) => m * MINUTE);
  const pxPerMs = (s.right - s.left) / (s.to - s.from || 1);
  const step = steps.find((st) => st * pxPerMs >= minPx) ?? steps[steps.length - 1];
  const ticks: { x: number; label: string }[] = [];
  // Whole local minutes are whole UTC minutes; steps up to an hour divide the hour.
  for (let time = Math.ceil(s.from / step) * step; time <= s.to; time += step) {
    ticks.push({ x: s.x(time), label: localTime(new Date(time).toISOString()) });
  }
  return ticks;
}

/** The delay at the start, the end and the worst along the way, from the samples (seconds). */
export function delaySummary(r: TripRun): {
  start: number | null;
  end: number | null;
  max: number | null;
} {
  const delays = r.samples.map((p) => p.delay).filter((d): d is number => d !== null);
  const first = r.trip.runs[0];
  const last = r.trip.runs[r.trip.runs.length - 1];
  return {
    start: first?.startDelay ?? null,
    end: last?.endDelay ?? null,
    max: delays.length ? Math.max(...delays) : null,
  };
}

/** What happened on the way, in time order: changes of bus, stands, and where the trip ended early. */
export function tripEvents(r: TripRun): { at: string; text: string }[] {
  const trip = r.trip;
  const events: { at: string; text: string }[] = [];
  trip.runs.slice(1).forEach((run, i) => {
    events.push({
      at: run.departure,
      text: t('trips.events.changed', {
        time: localTime(run.departure),
        from: trip.runs[i].bus,
        to: run.bus,
        stop: run.fromName,
      }),
    });
  });
  for (const st of r.stands) {
    events.push({
      at: st.from,
      text: t('trips.events.stood', {
        time: localTime(st.from),
        bus: st.bus,
        minutes: st.minutes,
        stop: st.stop,
        growth: st.delayGrowth === null ? '–' : departureDelayText(st.delayGrowth),
      }),
    });
  }
  const last = trip.runs[trip.runs.length - 1];
  if (last && (trip.kind === 'short-turned' || trip.kind === 'lost')) {
    events.push({
      at: last.arrival,
      text: t(trip.kind === 'lost' ? 'trips.events.lost' : 'trips.events.shortTurned', {
        time: localTime(last.arrival),
        stop: last.toName,
        index: last.toStop,
        total: trip.stops,
      }),
    });
  }
  return events.sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
}

/** The samples of one value as points of a line, broken where the bus changes or nothing was sampled for 2 min. */
export function linePoints<P extends { at: string; bus: string }>(
  samples: P[],
  value: (p: P) => number | null,
): { time: number; value: number }[][] {
  const lines: { time: number; value: number }[][] = [];
  let line: { time: number; value: number }[] = [];
  let previous: P | null = null;
  for (const p of samples) {
    const v = value(p);
    const broken =
      previous !== null &&
      (previous.bus !== p.bus || Date.parse(p.at) - Date.parse(previous.at) > 2 * MINUTE);
    if (v === null || broken) {
      if (line.length) lines.push(line);
      line = [];
    }
    if (v !== null) line.push({ time: Date.parse(p.at), value: v });
    previous = p;
  }
  if (line.length) lines.push(line);
  return lines;
}

/** How long the bus stood at a stop: "Stood 0:35 at the stop", "Didn't stop", or nothing when not seen near it. */
export function dwellText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '';
  return seconds === 0
    ? t('trips.stops.noStop')
    : t('trips.stops.stood', { time: formatDelay(seconds) });
}

/** The stops the bus stood at longest, longest first: at most `count`, each at least `minSeconds`. */
export function longestHalts(
  stops: TripDetailStop[],
  count = 5,
  minSeconds = 20,
): TripDetailStop[] {
  return stops
    .filter((s) => (s.dwellSeconds ?? 0) >= minSeconds)
    .sort((a, b) => b.dwellSeconds! - a.dwellSeconds! || a.index - b.index)
    .slice(0, count);
}
