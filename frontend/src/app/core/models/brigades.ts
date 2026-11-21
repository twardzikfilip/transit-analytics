/**
 * Brigades (vehicle duties): every one in the timetable (GET /api/brigades), and one brigade's day, each trip
 * scheduled with what actually ran (GET /api/brigades/{brigade}/day).
 */

export interface BrigadeAgency {
  /** The agency's timetable id, e.g. "BKM". */
  id: string;
  name: string;
}

export interface BrigadeRef {
  brigade: string;
  /** The lines it runs, on any day. */
  lines: string[];
}

/** Every brigade of an agency, whatever day it runs. */
export interface BrigadeList {
  /** The agency the brigades are of; null when no agency names brigades. */
  agency: string | null;
  agencies: BrigadeAgency[];
  brigades: BrigadeRef[];
}

/** A trip's outcome, as on Abnormal trips. */
export type BrigadeTripKind =
  | 'completed'
  | 'detour'
  | 'vehicle-changed'
  | 'no-signal'
  | 'short-turned'
  | 'lost'
  | 'missed'
  | 'cancelled'
  | 'not-recorded'
  | 'pending';

/** Part of a trip one bus ran, from the first stop (1-based) it was seen heading to, to the last. */
export interface ActualRun {
  bus: string;
  fromStop: number;
  toStop: number;
  fromName: string;
  toName: string;
  /** Scheduled time at its first stop plus its delay then; `arrival` likewise at its last. */
  departure: string;
  arrival: string;
  /** Seconds late (negative: early) setting off, and at its last report. */
  startDelay: number | null;
  endDelay: number | null;
}

/** Part of a trip missing from what ran, in scheduled time. */
export interface MissingPart {
  fromStop: number;
  toStop: number;
  from: string;
  to: string;
  /** Not run (lost, missed, cancelled, turned short), or not seen (the bus stopped reporting, probably ran it). */
  why: 'not-run' | 'not-seen';
}

export interface BrigadeTrip {
  tripId: string;
  line: string;
  headsign: string | null;
  from: string;
  to: string;
  direction: number;
  departure: string;
  arrival: string;
  stops: number;
  kind: BrigadeTripKind;
  note: string | null;
  /** One per bus, in order: more than one when the bus was changed. */
  runs: ActualRun[];
  missing: MissingPart[];
}

export interface BrigadeSummary {
  trips: number;
  /** Trips over and recorded. */
  judged: number;
  completed: number;
  abnormal: number;
  /** Judged trips leaving between 1 min early and 3 min late, of `withStartDelay`. */
  onTime: number;
  withStartDelay: number;
  medianStartDelay: number | null;
  maxDelay: number | null;
  buses: string[];
}

export interface BrigadeDay {
  brigade: string;
  serviceDay: string;
  summary: BrigadeSummary;
  trips: BrigadeTrip[];
}
