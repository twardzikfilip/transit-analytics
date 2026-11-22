/**
 * Abnormal trips: scheduled trips not run, run only partly, or off their route, from recorded positions matched to
 * the timetable by brigade and scheduled time.
 */

/** Kinds of abnormal trip. */
export type TripAnomalyKind =
  'cancelled' | 'missed' | 'lost' | 'short-turned' | 'detour' | 'vehicle-changed' | 'no-signal';

/** Outcomes of the trips scheduled to leave in the window. */
export interface TripAnomalySummary {
  scheduled: number;
  /** Recorded throughout and over (or already abnormal). */
  judged: number;
  completed: number;
  detours: number;
  /** Run in full, but by two buses: another of the brigade took over midway. */
  vehicleChanged: number;
  noSignal: number;
  shortTurned: number;
  lost: number;
  missed: number;
  cancelled: number;
  /** Not judged: a gap in recording during the trip. */
  notRecorded: number;
  /** Not judged yet: under way, or not due. */
  pending: number;
  /** Of the bus changes, those whose replaced bus wasn't used again that day: possibly broken down. */
  changedBrokenDown: number;
  /** Of the bus changes, those whose replaced bus was used later on another line or brigade. */
  changedReassigned: number;
}

/** On a change of bus, what became of the replaced bus that day. */
export type ChangeFate = 'broken-down' | 'reassigned' | 'returned';

export interface TripAnomalyLine {
  line: string;
  judged: number;
  completed: number;
  detours: number;
  vehicleChanged: number;
  noSignal: number;
  shortTurned: number;
  lost: number;
  missed: number;
  cancelled: number;
}

export interface AbnormalTrip {
  kind: TripAnomalyKind;
  /** What was seen, in phrases. */
  note: Phrase[];
  tripId: string;
  line: string;
  brigade: string | null;
  headsign: string | null;
  firstStop: string;
  lastStop: string;
  scheduledDeparture: string;
  scheduledArrival: string;
  stops: number;
  /** Buses seen on it, in order: two or more when the bus was changed midway. */
  vehicles: string[];
  /** Stops it was seen heading to, first and last (1-based); null when not run. */
  fromStop: number | null;
  toStop: number | null;
  coveredFrom: string | null;
  coveredTo: string | null;
  firstSeen: string | null;
  lastSeen: string | null;
  startDelay: number | null;
  maxDelay: number | null;
  endDelay: number | null;
  detourMeters: number | null;
  detourMinutes: number | null;
  detourFrom: string | null;
  detourTo: string | null;
  /** On a detour: the bus's path [lat, lon] (its reports off the route, a few either side), and the usual route
   * around it; empty otherwise. */
  detourPath: [number, number][];
  detourRoute: [number, number][];
  changeFate: ChangeFate | null;
  /** The line and brigade the replacement bus ran before taking over; null when it hadn't run another that day. */
  replacementLine: string | null;
  replacementBrigade: string | null;
  /** How long the trip stood still at the change: from the old bus stopping to the new bus's first report. */
  changeMinutes: number | null;
  /** The old bus's delay when it stopped, and the new bus's first (seconds). */
  delayBeforeChange: number | null;
  delayAfterChange: number | null;
  /** The stop the old bus was heading to when it stopped, and the one the new bus first headed to. */
  changeStop: string | null;
  joinStop: string | null;
  /** Stops between the two neither bus was seen heading to; 0 when the new bus took over at the same place. */
  skippedStops: number | null;
  /** On a change of bus, when the replaced bus stopped for good. */
  changeAt: string | null;
  /** When the replaced bus wasn't used again: how long it stood where it stopped (minutes)… */
  stoodMinutes: number | null;
  /** …what it did next… */
  stoodThen: StoodThen | null;
  /** …and its last report that day, and how far that was from where it stopped. */
  stoodLastSeen: string | null;
  stoodLastSeenMeters: number | null;
  /** On a change of bus, what happened step by step, in time order; empty otherwise. */
  story: IncidentStep[];
  /** On a change of bus, what each bus did when, for a time line; empty otherwise. */
  timeline: IncidentSpan[];
}

export interface IncidentStep {
  at: string;
  text: Phrase[];
}

/**
 * A sentence (or part of one) about a trip, for the page to write in the reader's language: a key under `tripNotes`
 * and its values; a value can itself be a phrase. Written by `phrasesText` (`shared/i18n/phrases.ts`).
 */
export interface Phrase {
  key: string;
  params: Record<string, string | number | Phrase>;
}

/**
 * A stretch of a bus's time around a change of bus: running (the replaced bus on the trip), standing (where it
 * stopped), moving (off without a brigade), approach (the replacement on its way, without the brigade) or service
 * (on a brigade: the replacement on the trip and on; for the replaced bus used again, a moment).
 */
export interface IncidentSpan {
  vehicle: string;
  kind: 'running' | 'standing' | 'moving' | 'approach' | 'service';
  from: string;
  to: string;
}

/**
 * A replaced bus not used again, after standing where it stopped: stopped reporting while recording went on
 * (switched off, or towed with its tracker off), moved away without running a brigade, or still standing there.
 */
export type StoodThen = 'vanished' | 'drove-off' | 'still-there';

export interface TripAnomalyReport {
  from: string;
  to: string;
  summary: TripAnomalySummary;
  /** Lines with the most abnormal trips first. */
  lines: TripAnomalyLine[];
  /** Abnormal trips in the window; `trips` holds the latest of them. */
  tripsTotal: number;
  /** The latest scheduled departure first. */
  trips: AbnormalTrip[];
}
