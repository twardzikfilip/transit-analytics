import { BrigadeTrip } from '../../core/models/models';
import { localTime } from '../../shared/format/time';
import { TRIP_COLORS, departureDelayText, shortStop } from './brigade-format';
import { t as tr } from '../../core/i18n/t';

/**
 * Where everything goes on the brigade day chart: pure functions of the trips and the width there is, so they can be
 * tested without drawing.
 */

export const LEFT = 78;
export const RIGHT = 16;
/** Lanes, top to bottom: hour labels, scheduled labels (two rows), scheduled bars, actual bars, bus labels. */
export const AXIS_Y = 14;
export const SCHED_LABEL_Y = [34, 48];
export const SCHED_Y = 56;
export const BAR_H = 16;
/** Actual runs in two rows, consecutive trips alternating, so a late trip running into the next stays clear. */
export const ACTUAL_Y = [96, 116];
export const BUS_LABEL_Y = [146, 160];
export const CHART_HEIGHT = 170;
/** At least this many px per minute: a full day scrolls sideways rather than squeezing the trips. */
export const MIN_PX_PER_MIN = 1.6;
/** About this many px per character of an 11 px label. */
const CHAR_PX = 6.3;
const MINUTE = 60_000;

/** A label cut to fit a width, with an ellipsis; empty when not even a few characters fit. */
export function fit(text: string, px: number): string {
  const chars = Math.floor(px / CHAR_PX);
  if (chars >= text.length) return text;
  return chars < 4 ? '' : text.slice(0, chars - 1) + '…';
}

export interface Bar {
  x: number;
  width: number;
}

/** The time axis: the span drawn, px per minute, the chart's width, and where a time falls. */
export interface TimeScale {
  from: number;
  to: number;
  ppm: number;
  width: number;
  x(time: string | number): number;
}

/**
 * The time span drawn (a quarter of an hour either side of everything scheduled or run), fitted to the width there
 * is, but never tighter than {@link MIN_PX_PER_MIN}.
 */
export function timeScale(trips: BrigadeTrip[], available: number): TimeScale {
  const times = trips.flatMap((t) => [
    Date.parse(t.departure),
    Date.parse(t.arrival),
    ...t.runs.flatMap((r) => [Date.parse(r.departure), Date.parse(r.arrival)]),
  ]);
  const from = times.length ? Math.min(...times) - 15 * MINUTE : 0;
  const to = times.length ? Math.max(...times) + 15 * MINUTE : 60 * MINUTE;
  const minutes = (to - from) / MINUTE;
  const ppm = Math.max(MIN_PX_PER_MIN, (available - LEFT - RIGHT) / minutes);
  return {
    from,
    to,
    ppm,
    width: Math.round(LEFT + RIGHT + minutes * ppm),
    x: (time) =>
      LEFT + (((typeof time === 'number' ? time : Date.parse(time)) - from) / MINUTE) * ppm,
  };
}

function bar(scale: TimeScale, from: string, to: string): Bar {
  const x = scale.x(from);
  return { x, width: Math.max(2, scale.x(to) - x) };
}

/** Whole hours (local, which are whole UTC hours in Poland) inside the span; every other one when tight. */
export function hourTicks(scale: TimeScale): { x: number; label: string }[] {
  const hour = 60 * MINUTE;
  const step = scale.ppm * 60 < 48 ? 2 * hour : hour;
  const ticks: { x: number; label: string }[] = [];
  for (let t = Math.ceil(scale.from / hour) * hour; t <= scale.to; t += step) {
    ticks.push({ x: scale.x(t), label: localTime(new Date(t).toISOString()) });
  }
  return ticks;
}

/** Each trip's row: its bars, labels cut to their room, markers, and the area that catches the pointer. */
export function tripRows(trips: BrigadeTrip[], scale: TimeScale) {
  return trips.map((t, i) => {
    const scheduled = bar(scale, t.departure, t.arrival);
    // Labels of a row may run up to the start of the next trip in the same label row.
    const nextInRow = trips[i + 2];
    const room = (nextInRow ? scale.x(nextInRow.departure) : scale.width) - scheduled.x - 4;
    const runs = t.runs.map((r) => bar(scale, r.departure, r.arrival));
    const missing = t.missing.map((m) => ({ ...bar(scale, m.from, m.to), why: m.why }));
    const first = t.runs[0];
    const last = t.runs[t.runs.length - 1];
    const busX = first ? scale.x(first.departure) : scheduled.x;
    const nextBus = nextInRow?.runs[0] ? scale.x(nextInRow.runs[0].departure) : scale.width;
    const busLabel = first
      ? `${t.runs.map((r) => r.bus).join(' → ')} ${departureDelayText(first.startDelay)}`
      : t.kind === 'pending'
        ? tr('brigades.chart.upcoming')
        : t.kind === 'not-recorded'
          ? tr('brigades.chart.noData')
          : '';
    const allX = [
      scheduled.x,
      scheduled.x + scheduled.width,
      ...runs.flatMap((r) => [r.x, r.x + r.width]),
    ];
    return {
      trip: t,
      y: ACTUAL_Y[i % 2],
      color: TRIP_COLORS[i % 2],
      scheduled,
      label: fit(`${localTime(t.departure)} ${t.route} → ${shortStop(t.headsign ?? t.to)}`, room),
      runs,
      missing,
      noData:
        !t.runs.length && !t.missing.length && (t.kind === 'not-recorded' || t.kind === 'pending')
          ? scheduled
          : null,
      connector: first ? { from: scheduled.x, to: scale.x(first.departure) } : null,
      changes: t.runs.slice(1).map((r) => scale.x(r.departure)),
      lostAt: t.kind === 'lost' && last ? scale.x(last.arrival) : null,
      detourAt:
        t.kind === 'detour' && first
          ? (scale.x(first.departure) + scale.x(last.arrival)) / 2
          : null,
      busX,
      busLabel: fit(busLabel, nextBus - busX - 4),
      hit: { x: Math.min(...allX) - 2, width: Math.max(...allX) - Math.min(...allX) + 4 },
    };
  });
}
