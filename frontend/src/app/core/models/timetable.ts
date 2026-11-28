/** The timetable (GTFS): route kinds, stops and stop times, and the import result. */

/** Kind of route: city, suburban (100 and up), night (N1…) or regional. */
export type RouteSubtype = 'urban' | 'suburban' | 'night_urban' | 'regional';

/**
 * How a route runs relative to the city centre: radial (centre to periphery), diameter (one side to the opposite one
 * through the centre), arcantial (two radial legs at an angle, joined in or by the centre), tangential (round the
 * centre, outer parts to each other) or feeder (from the suburbs, ending short of the centre).
 */
export type RouteGeometry = 'radial' | 'diameter' | 'arcantial' | 'tangential' | 'feeder';

/** How much a route carries, strongest first (rules in the backend's classification/routes.yml). */
export type RouteCapacity = 'main' | 'primary' | 'secondary' | 'tertiary' | 'irregular';

/** A stop or station (GTFS stop). */
export interface Stop {
  id: number;
  /** The timetable (GTFS feed) it is from: bialystok (BKM's) or another operator's; see timetable.feeds. */
  feed: string;
  code: string | null;
  name: string;
  latitude: number | null;
  longitude: number | null;
  zoneId: string | null;
}

/** When a trip calls at a stop (GTFS stop_time). Times are "HH:MM:SS" and may exceed 24:00:00. */
export interface StopTime {
  id: number;
  tripId: number;
  tripGtfsId: string;
  tripHeadsign: string | null;
  /** Which days the trip runs. */
  serviceId: string;
  routeName: string;
  stopId: number;
  stopName: string;
  stopSequence: number;
  arrivalTime: string | null;
  departureTime: string | null;
  stopHeadsign: string | null;
  /** GTFS: 0 = regular, 1 = none, 2 = phone the agency, 3 = ask the driver. */
  pickupType: number | null;
  dropOffType: number | null;
}

export interface GtfsImportResult {
  feed: string;
  source: string;
  downloadedBytes: number;
  importedAt: string;
  durationMs: number;
  /** Rows now in each table. */
  rows: Record<string, number>;
  /** Feed rows per file that were skipped because they point at a missing route, trip or stop. */
  skipped: Record<string, number>;
  ignoredFiles: string[];
  /** The download was the same as the last import's: nothing was imported, and importedAt is that import's time. */
  unchanged: boolean;
}

/** A timetable feed that can be imported, with its last import. */
export interface GtfsFeed {
  /** "bialystok", or another operator's feed ("rail", "wschodexpress"...). */
  key: string;
  /** What the feed is, as the server's settings name it; null for some. */
  name: string | null;
  source: string;
  last: { fingerprint: string; importedAt: string; downloadedBytes: number } | null;
}

/** Until when the imported timetables run. */
export interface TimetableCoverage {
  today: string;
  /** A feed with no more than this many days left is ending. */
  warnDays: number;
  feeds: FeedCoverage[];
}

export interface FeedCoverage {
  feed: string;
  /** The last day with trips ("2026-11-28"); null when nothing is loaded. */
  lastDay: string | null;
  /** 0 when today is the last day, negative once it has ended. */
  daysLeft: number | null;
  ending: boolean;
  /** Days continued with its last known week's services, in order ("2026-11-29"). */
  assumed: string[];
}
