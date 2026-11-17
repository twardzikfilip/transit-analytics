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
  /** What was seen, in words. */
  note: string;
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
}

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
