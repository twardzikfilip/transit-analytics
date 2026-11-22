import { ParamMap } from '@angular/router';
import { WindowQuery } from '../../core/models/models';
import { toLocalInput } from '../format/time';

/**
 * The time window every report page shares, kept in the URL: `?range=1h` (a preset ending now) or
 * `?from=2026-11-16T05:00&to=2026-11-16T17:00` (local time, as a datetime-local input gives it), and `?agency=BKM`
 * (repeatable) for the operators.
 */

export interface WindowPreset {
  key: string;
  label: string;
  /** Length; null for all time (from the first recording). */
  ms: number | null;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** The presets; `label` is a translation key. */
export const WINDOW_PRESETS: readonly WindowPreset[] = [
  { key: '1m', label: 'time.presets.1m', ms: MINUTE },
  { key: '5m', label: 'time.presets.5m', ms: 5 * MINUTE },
  { key: '30m', label: 'time.presets.30m', ms: 30 * MINUTE },
  { key: '1h', label: 'time.presets.1h', ms: HOUR },
  { key: '2h', label: 'time.presets.2h', ms: 2 * HOUR },
  { key: '12h', label: 'time.presets.12h', ms: 12 * HOUR },
  { key: '24h', label: 'time.presets.24h', ms: DAY },
  { key: '3d', label: 'time.presets.3d', ms: 3 * DAY },
  { key: '1w', label: 'time.presets.1w', ms: 7 * DAY },
  { key: '1mo', label: 'time.presets.1mo', ms: 30 * DAY },
  { key: 'all', label: 'time.presets.all', ms: null },
];

/** Positions are recorded every 10 seconds; windows up to an hour end at the next poll, longer ones each minute. */
export const POLL_MS = 10_000;
const SHORT_WINDOW_MS = HOUR;

export interface TimeWindow {
  /** Null for all time: from the first recording. */
  from: Date | null;
  to: Date;
  /** The preset's key, or null for a custom from/to. */
  preset: string | null;
}

/** The window in the URL: a valid custom from/to, else the preset, else the page's default preset. */
export function resolveWindow(
  params: { range: string | null; from: string | null; to: string | null },
  now: Date,
  defaultPreset: string,
): TimeWindow {
  const from = params.from ? new Date(params.from) : null;
  const to = params.to ? new Date(params.to) : null;
  if (from && to && !isNaN(from.getTime()) && !isNaN(to.getTime()) && from < to) {
    return { from, to, preset: null };
  }
  const preset =
    WINDOW_PRESETS.find((p) => p.key === params.range) ??
    WINDOW_PRESETS.find((p) => p.key === defaultPreset) ??
    WINDOW_PRESETS[6];
  const step = preset.ms !== null && preset.ms <= SHORT_WINDOW_MS ? POLL_MS : MINUTE;
  const end = new Date(Math.floor(now.getTime() / step) * step + step);
  return {
    from: preset.ms === null ? null : new Date(end.getTime() - preset.ms),
    to: end,
    preset: preset.key,
  };
}

/** The window in a URL's query parameters. */
export function readWindow(params: ParamMap, defaultPreset: string, now = new Date()): TimeWindow {
  return resolveWindow(
    { range: params.get('range'), from: params.get('from'), to: params.get('to') },
    now,
    defaultPreset,
  );
}

/** The operators chosen in the URL; empty for all. */
export function readAgencies(params: ParamMap): string[] {
  return params.getAll('agency');
}

export function windowQuery(w: TimeWindow): WindowQuery {
  return { from: w.from?.toISOString() ?? null, to: w.to.toISOString() };
}

/** The URL's window parameters for a preset, or for a custom from/to (local datetime-local values). */
export function presetParams(key: string): Record<string, string | null> {
  return { range: key, from: null, to: null };
}

export function customParams(
  w: TimeWindow,
  which: 'from' | 'to',
  value: string,
): Record<string, string | null> | null {
  if (!value) return null;
  const from = which === 'from' ? value : toLocalInput(w.from ?? new Date(w.to.getTime() - DAY));
  const to = which === 'to' ? value : toLocalInput(w.to);
  return { range: null, from, to };
}

/** Length in ms; null for all time. */
export function windowLength(w: TimeWindow): number | null {
  return w.from ? w.to.getTime() - w.from.getTime() : null;
}

/** How often a preset window moves forward (and a page reloads): each poll for short windows, else each minute. */
export function refreshMs(w: TimeWindow): number {
  const length = windowLength(w);
  return length !== null && length <= SHORT_WINDOW_MS ? POLL_MS : MINUTE;
}

/** Same window and operators: whether a reload is needed. */
export function sameQuery<Q extends { window: TimeWindow; agencies?: string[] }>(
  a: Q,
  b: Q,
): boolean {
  return (
    a.window.preset === b.window.preset &&
    (a.window.preset !== null ||
      (a.window.from?.getTime() === b.window.from?.getTime() &&
        a.window.to.getTime() === b.window.to.getTime())) &&
    (a.agencies ?? []).join() === (b.agencies ?? []).join()
  );
}
