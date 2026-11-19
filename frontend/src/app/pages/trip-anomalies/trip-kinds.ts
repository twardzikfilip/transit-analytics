import {
  AbnormalTrip,
  ChangeFate,
  TripAnomalyKind,
  TripAnomalyLine,
  TripAnomalySummary,
} from '../../core/models/models';
import { duration, formatDelay } from '../../shared/format/time';

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
    key: 'vehicle-changed',
    label: 'Bus changed',
    description:
      'Run in full, but by two buses: one ran part of it and another bus of the brigade took over midway (a breakdown, typically).',
    count: 'vehicleChanged',
  },
  {
    key: 'no-signal',
    label: 'No signal',
    description:
      'The bus stopped reporting partway (or started late) but was back for its next trip: most likely run, unseen.',
    count: 'noSignal',
  },
];

/** What became of the replaced bus, in short. */
export function changeFateLabel(fate: ChangeFate): string {
  switch (fate) {
    case 'broken-down':
      return 'Possibly broken down';
    case 'reassigned':
      return 'Old bus reassigned';
    case 'returned':
      return 'Old bus back later';
  }
}

/** Where the replacement bus came from: "from line 29 (029-05)", "from the depot". */
export function replacementFrom(trip: AbnormalTrip): string {
  return trip.replacementLine
    ? `New bus from line ${trip.replacementLine} (${trip.replacementBrigade})`
    : 'New bus from the depot';
}

/** Where the new bus joined: "Same place" or "Joined 3 stops further on". */
export function joinedAt(trip: AbnormalTrip): string {
  if (!trip.skippedStops) return 'Took over at the same place';
  return `Joined ${trip.skippedStops} ${trip.skippedStops === 1 ? 'stop' : 'stops'} further on`;
}

/**
 * What the replaced bus did when it wasn't used again: "Old bus stood 34 min, then went silent"; null when there's
 * nothing to tell (no change of bus, or the old bus was used again).
 */
export function stoodLabel(trip: AbnormalTrip): string | null {
  if (trip.stoodThen === null || trip.stoodMinutes === null) return null;
  const stood = trip.stoodMinutes ? `Old bus stood ${duration(trip.stoodMinutes)}` : 'Old bus';
  switch (trip.stoodThen) {
    case 'still-there':
      return `Old bus still standing there (${duration(trip.stoodMinutes)})`;
    case 'vanished':
      return trip.stoodMinutes ? `${stood}, then went silent` : 'Old bus went silent right away';
    case 'drove-off': {
      const m = trip.stoodLastSeenMeters ?? 0;
      const away = m < 1000 ? `${Math.round(m / 50) * 50} m` : `${(m / 1000).toFixed(1)} km`;
      return `${trip.stoodMinutes ? `${stood}, then` : 'Old bus'} moved off ${away}`;
    }
  }
}

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
