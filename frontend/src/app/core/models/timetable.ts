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
}

/** Kind of line: city, suburban (100 and up), night (N1…) or regional. */
export type RouteSubtype = 'urban' | 'suburban' | 'night_urban' | 'regional';

/** A stop or station (GTFS stop). */
export interface Stop {
  id: number;
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

/** One run of a route (GTFS trip). */
export interface Trip {
  id: number;
  gtfsId: string;
  routeId: number;
  routeName: string;
  routeColor: string | null;
  serviceId: string;
  headsign: string | null;
  shortName: string | null;
  /** GTFS direction_id: 0 or 1. */
  direction: number | null;
  blockId: string | null;
  shapeId: string | null;
  /** GTFS: 0 = unknown, 1 = accessible, 2 = not accessible. */
  wheelchairAccessible: number | null;
  bikesAllowed: number | null;
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
