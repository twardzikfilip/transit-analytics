import {
  AbnormalTrip,
  ChangeFate,
  TripAnomalyKind,
  TripAnomalyLine,
  TripAnomalySummary,
} from '../../core/models/models';
import { duration, formatDelay } from '../../shared/format/time';
import { shareOf } from '../../shared/format/number';
import { t, tn } from '../../core/i18n/t';

/** Kinds of abnormal trip, the most serious first, with where their count is in the summary and per line. */
export const KINDS: {
  key: TripAnomalyKind;
  /** Translation keys of its name and its explanation. */
  label: string;
  description: string;
  count: keyof TripAnomalySummary & keyof TripAnomalyLine;
}[] = [
  {
    key: 'cancelled',
    label: 'anomalies.kinds.cancelled',
    description: 'anomalies.desc.cancelled',
    count: 'cancelled',
  },
  {
    key: 'missed',
    label: 'anomalies.kinds.missed',
    description: 'anomalies.desc.missed',
    count: 'missed',
  },
  {
    key: 'lost',
    label: 'anomalies.kinds.lost',
    description: 'anomalies.desc.lost',
    count: 'lost',
  },
  {
    key: 'short-turned',
    label: 'anomalies.kinds.short-turned',
    description: 'anomalies.desc.short-turned',
    count: 'shortTurned',
  },
  {
    key: 'detour',
    label: 'anomalies.kinds.detour',
    description: 'anomalies.desc.detour',
    count: 'detours',
  },
  {
    key: 'vehicle-changed',
    label: 'anomalies.kinds.vehicle-changed',
    description: 'anomalies.desc.vehicle-changed',
    count: 'vehicleChanged',
  },
  {
    key: 'no-signal',
    label: 'anomalies.kinds.no-signal',
    description: 'anomalies.desc.no-signal',
    count: 'noSignal',
  },
];

/** What became of the replaced bus, in short. */
export function changeFateLabel(fate: ChangeFate): string {
  switch (fate) {
    case 'broken-down':
    case 'reassigned':
    case 'returned':
      return t(`anomalies.fate.${fate}`);
  }
}

/** Where the replacement bus came from: "from line 29 (029-05)", "from the depot". */
export function replacementFrom(trip: AbnormalTrip): string {
  return trip.replacementLine
    ? t('anomalies.fromLine', { line: trip.replacementLine, brigade: trip.replacementBrigade })
    : t('anomalies.fromDepot');
}

/** Where the new bus joined: "Same place" or "Joined 3 stops further on". */
export function joinedAt(trip: AbnormalTrip): string {
  if (!trip.skippedStops) return t('anomalies.samePlace');
  return t('anomalies.joined', { stops: tn('anomalies.stops', trip.skippedStops) });
}

/**
 * What the replaced bus did when it wasn't used again: "Old bus stood 34 min, then went silent"; null when there's
 * nothing to tell (no change of bus, or the old bus was used again).
 */
export function stoodLabel(trip: AbnormalTrip): string | null {
  if (trip.stoodThen === null || trip.stoodMinutes === null) return null;
  const stood = t('anomalies.oldBusStood', { time: duration(trip.stoodMinutes) });
  switch (trip.stoodThen) {
    case 'still-there':
      return t('anomalies.stillThere', { time: duration(trip.stoodMinutes) });
    case 'vanished':
      return trip.stoodMinutes ? t('anomalies.thenSilent', { stood }) : t('anomalies.silentAtOnce');
    case 'drove-off': {
      const m = trip.stoodLastSeenMeters ?? 0;
      const away = m < 1000 ? `${Math.round(m / 50) * 50} m` : `${(m / 1000).toFixed(1)} km`;
      return trip.stoodMinutes
        ? t('anomalies.movedOff', { stood, away })
        : t('anomalies.oldMovedOff', { away });
    }
  }
}

export function kindLabel(kind: TripAnomalyKind): string {
  const key = KINDS.find((k) => k.key === kind)?.label;
  return key ? t(key) : kind;
}

/** A share as a percentage: "4.2%", "0%"; "—" without a total. */
export function percent(part: number, total: number): string {
  return shareOf(part, total, { adaptive: true, empty: '—' });
}

/** A delay with its sign ("+2:15", "−0:40") or "—". */
export function signedDelay(seconds: number | null): string {
  if (seconds === null) return '—';
  return (seconds > 0 ? '+' : '') + formatDelay(seconds);
}

/** "Stops 3–14 of 25" or "Not run". */
export function coverage(trip: AbnormalTrip): string {
  if (trip.fromStop === null || trip.toStop === null) return t('anomalies.notRun');
  return t('anomalies.stopsOf', { from: trip.fromStop, to: trip.toStop, total: trip.stops });
}

/** Abnormal trips per line, all kinds together. */
export function abnormalCount(line: TripAnomalyLine): number {
  return line.judged - line.completed;
}
