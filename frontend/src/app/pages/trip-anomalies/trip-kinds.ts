import {
  AbnormalTrip,
  TripAnomalyKind,
  TripAnomalyLine,
  TripAnomalySummary,
  TripWindow,
} from '../../core/models/models';
import { formatDelay } from '../../shared/format/time';

/** The page's time windows, ending now; short ones refresh every minute. */
export const TRIP_WINDOWS: { key: TripWindow; label: string; refresh: boolean }[] = [
  { key: '15m', label: '15 min', refresh: true },
  { key: '1h', label: '1 hour', refresh: true },
  { key: '3h', label: '3 hours', refresh: true },
  { key: '6h', label: '6 hours', refresh: true },
  { key: 'day', label: '24 hours', refresh: false },
  { key: 'week', label: '7 days', refresh: false },
  { key: 'month', label: '30 days', refresh: false },
];
export const DEFAULT_TRIP_WINDOW: TripWindow = 'day';

/** The window in the URL (?window=1h), else the default. */
export function parseTripWindow(value: string | null): TripWindow {
  return TRIP_WINDOWS.find((w) => w.key === value)?.key ?? DEFAULT_TRIP_WINDOW;
}

/** Kinds of abnormal trip, the most serious first, with where their count is in the summary and per line. */
export const KINDS: {
  key: TripAnomalyKind;
  label: string;
  description: string;
  count: keyof TripAnomalySummary & keyof TripAnomalyLine;
}[] = [
  {
    key: 'cancelled',
    label: 'Cancelled',
    description: 'Not run: no bus ran the brigade at all that day.',
    count: 'cancelled',
  },
  {
    key: 'missed',
    label: 'Missed',
    description: "Not run, while the brigade's bus ran other trips that day.",
    count: 'missed',
  },
  {
    key: 'lost',
    label: 'Lost',
    description: "The bus stopped reporting midway and didn't come back to its brigade.",
    count: 'lost',
  },
  {
    key: 'short-turned',
    label: 'Short-turned',
    description:
      'Run only partly: the bus, reporting all along, joined midway or left before the end.',
    count: 'shortTurned',
  },
  {
    key: 'detour',
    label: 'Detour',
    description: 'Run in full, but off its route (over 150 m away) for a while.',
    count: 'detours',
  },
  {
    key: 'no-signal',
    label: 'No signal',
    description:
      'The bus stopped reporting partway (or started late) but was back for its next trip: most likely run, unseen.',
    count: 'noSignal',
  },
];

export function kindLabel(kind: TripAnomalyKind): string {
  return KINDS.find((k) => k.key === kind)?.label ?? kind;
}

/** A share as a percentage: "4.2%", "0%"; "—" without a total. */
export function percent(part: number, total: number): string {
  if (!total) return '—';
  const p = (part / total) * 100;
  return p === 0 ? '0%' : p < 10 ? `${p.toFixed(1)}%` : `${Math.round(p)}%`;
}

/** A delay with its sign ("+2:15", "−0:40") or "—". */
export function signedDelay(seconds: number | null): string {
  if (seconds === null) return '—';
  return (seconds > 0 ? '+' : '') + formatDelay(seconds);
}

/** "Stops 3–14 of 25" or "Not run". */
export function coverage(trip: AbnormalTrip): string {
  if (trip.fromStop === null || trip.toStop === null) return 'Not run';
  return `Stops ${trip.fromStop}–${trip.toStop} of ${trip.stops}`;
}

/** Abnormal trips per line, all kinds together. */
export function abnormalCount(line: TripAnomalyLine): number {
  return line.judged - line.completed;
}
