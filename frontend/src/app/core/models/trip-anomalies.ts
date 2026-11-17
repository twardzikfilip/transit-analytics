/**
 * Abnormal trips: scheduled trips not run, run only partly, or off their route, from recorded positions matched to
 * the timetable by brigade and scheduled time.
 */

/** The time windows of the page, ending now. */
export type TripWindow = '15m' | '1h' | '3h' | '6h' | 'day' | 'week' | 'month';

/** Kinds of abnormal trip. */
export type TripAnomalyKind =
  'cancelled' | 'missed' | 'lost' | 'short-turned' | 'detour' | 'no-signal';

/** Outcomes of the trips scheduled to leave in the window. */
export interface TripAnomalySummary {
  scheduled: number;
  /** Recorded throughout and over (or already abnormal). */
  judged: number;
  completed: number;
  detours: number;
  noSignal: number;
  shortTurned: number;
  lost: number;
  missed: number;
  cancelled: number;
  /** Not judged: a gap in recording during the trip. */
  notRecorded: number;
  /** Not judged yet: under way, or not due. */
  pending: number;
}

export interface TripAnomalyLine {
  line: string;
  judged: number;
  completed: number;
  detours: number;
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
}

export interface TripAnomalyReport {
  window: TripWindow;
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
