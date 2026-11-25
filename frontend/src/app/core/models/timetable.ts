/** The timetable (GTFS): routes, stops, trips and stop times, and the import result. */

/** A transit line (GTFS route). */
export interface Route {
  id: number;
  gtfsId: string;
  agencyId: number | null;
  agencyName: string | null;
  /** What riders see: short name, or long name when there is none. */
  name: string;
  shortName: string | null;
  longName: string | null;
  description: string | null;
  /** GTFS route type code, e.g. 3 = bus. */
  routeType: number;
  url: string | null;
  color: string | null;
  textColor: string | null;
  sortOrder: number | null;
  subtype: RouteSubtype;
  /** Null until classified (other operators' lines aren't). */
  geometry: RouteGeometry | null;
  capacityClass: RouteCapacity | null;
  /** The company operating it for BKM: KPKM, KPK or KZK. */
  operatingAgency: string | null;
}

/** Kind of line: city, suburban (100 and up), night (N1…) or regional. */
export type RouteSubtype = 'urban' | 'suburban' | 'night_urban' | 'regional';

/**
 * How a line runs relative to the city centre: radial (centre to periphery), diameter (one side to the opposite one
 * through the centre), arcantial (two radial legs at an angle, joined in or by the centre), tangential (round the
 * centre, outer parts to each other) or feeder (from the suburbs, ending short of the centre).
 */
export type RouteGeometry = 'radial' | 'diameter' | 'arcantial' | 'tangential' | 'feeder';

/** How much a line carries, strongest first (rules in the backend's classification/routes.yml). */
export type RouteCapacity = 'main' | 'primary' | 'secondary' | 'tertiary' | 'irregular';

/** A stop or station (GTFS stop). */
export interface Stop {
  id: number;
  /** The timetable (GTFS feed) it is from: bialystok (BKM's) or another operator's; see timetable.feeds. */
  feed: string;
  gtfsId: string;
  code: string | null;
  name: string;
  description: string | null;
  latitude: number | null;
  longitude: number | null;
  zoneId: string | null;
  url: string | null;
  locationType: number | null;
  parentStation: string | null;
  wheelchairBoarding: number | null;
  platformCode: string | null;
}

/** When a trip calls at a stop (GTFS stop_time). Times are "HH:MM:SS" and may exceed 24:00:00. */
export interface StopTime {
  id: number;
  tripId: number;
  tripGtfsId: string;
  tripHeadsign: string | null;
  /** Which days the trip runs; see calendar dates. */
  serviceId: string;
  routeId: number;
  routeName: string;
  routeColor: string | null;
  stopId: number;
  stopCode: string | null;
  stopName: string;
  stopSequence: number;
  arrivalTime: string | null;
  departureTime: string | null;
  stopHeadsign: string | null;
  /** GTFS: 0 = regular, 1 = none, 2 = phone the agency, 3 = ask the driver. */
  pickupType: number | null;
  dropOffType: number | null;
  shapeDistTraveled: number | null;
  timepoint: number | null;
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
