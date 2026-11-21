import { BrigadeTrip, BrigadeTripKind } from '../../core/models/models';
import { formatDelay, localTime } from '../../shared/format/time';
import { shareOf } from '../../shared/format/number';

/** The brigade chart's colours (see shared/palette.ts). */
export { CHANGE_COLOR, NOT_RUN_COLOR, NOT_SEEN_COLOR, TRIP_COLORS } from '../../shared/palette';

export const KIND_LABELS: Record<BrigadeTripKind, string> = {
  completed: 'Run in full',
  detour: 'Detour',
  'vehicle-changed': 'Bus changed',
  'no-signal': 'Bus not seen for part',
  'short-turned': 'Turned short',
  lost: 'Lost',
  missed: 'Missed',
  cancelled: 'Cancelled',
  'not-recorded': 'Not recorded',
  pending: 'Upcoming',
};

/** Outcomes that are a problem with the service (not of recording). */
export const PROBLEMS: BrigadeTripKind[] = [
  'detour',
  'vehicle-changed',
  'short-turned',
  'lost',
  'missed',
  'cancelled',
];

/** "+1:20" late, "−0:40" early, "on time" within 30 s; "–" unknown. */
export function delayText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '–';
  if (Math.abs(seconds) < 30) return 'on time';
  return (seconds > 0 ? '+' : '') + formatDelay(seconds);
}

/** "07:12–08:05". */
export function span(from: string, to: string): string {
  return `${localTime(from)}–${localTime(to)}`;
}

/** The last part of a stop name ("Choroszcz/Cmentarz" → "Cmentarz"), for short labels. */
export function shortStop(name: string): string {
  const parts = name.split('/');
  return parts[parts.length - 1].trim();
}

/** "103 → Choroszcz/Cmentarz". */
export function tripLabel(t: BrigadeTrip): string {
  return `${t.line} → ${t.headsign ?? t.to}`;
}

/** The buses that ran a trip, "209" or "209 → 486". */
export function busesText(t: BrigadeTrip): string {
  return t.runs.length ? t.runs.map((r) => r.bus).join(' → ') : '–';
}

/** When the trip actually ran: first bus's departure to the last bus's arrival. */
export function actualSpan(t: BrigadeTrip): string {
  if (!t.runs.length) return '–';
  return span(t.runs[0].departure, t.runs[t.runs.length - 1].arrival);
}

/** Share as "90%", or "–" of nothing. */
export function share(part: number, whole: number): string {
  return shareOf(part, whole, { digits: 0 });
}
