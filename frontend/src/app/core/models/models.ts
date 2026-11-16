export type RoleName = 'ADMIN' | 'GUEST';

export interface PageMeta {
  size: number;
  /** Zero-based. */
  number: number;
  totalElements: number;
  totalPages: number;
}

export interface Page<T> {
  content: T[];
  page: PageMeta;
}

/** List filters sent as query parameters; null and empty values are left out. */
export type Filters = Record<string, string | number | null>;

export interface Vehicle {
  id: number;
  vehicleNumber: string;
  type: string;
  capacity: number | null;
  agency: string | null;
  model: string | null;
}

export interface VehiclePosition {
  id: number;
  vehicleId: number;
  vehicleNumber: string;
  tripId: number | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  speed: number | null;
  recordedAt: string;
}

/** Live position from an external feed (zbiorkom.live); not stored in the backend. */
export interface LivePosition {
  /** Matching row in the vehicle table; null if it could not be registered yet. */
  vehicleId: number | null;
  vehicleNumber: string;
  type: string;
  agency: string | null;
  model: string | null;
  routeName: string | null;
  routeColor: string | null;
  brigade: string | null;
  headsign: string | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  /** Negative when early. */
  delaySeconds: number | null;
  timestamp: string | null;
  upcomingStops: UpcomingStop[];
}

export interface UpcomingStop {
  sequence: number | null;
  name: string;
  scheduledArrival: string | null;
  scheduledDeparture: string | null;
}

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
}

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
  vehicleId: number | null;
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

/** Vehicles running and mean delay over time (GET /api/positions/fleet). Buckets are made of polls (one a minute). */
export interface FleetSeries {
  from: string;
  to: string;
  /** Bucket size chosen by the backend: 60 (one poll) for windows up to 24 h, larger beyond. */
  stepSeconds: number;
  route: string | null;
  /** Only buckets with data; a missing bucket means nothing was recorded then. */
  points: FleetPoint[];
  summary: FleetSummary;
}

export interface FleetPoint {
  /** Start of the bucket. */
  time: string;
  /** Vehicles running: average per poll in the bucket. */
  vehicles: number;
  /** Most vehicles in one poll in the bucket. */
  peakVehicles: number;
  samples: number;
  meanDelaySeconds: number | null;
}

export interface FleetSummary {
  samples: number;
  /** Different vehicles seen at any time in the window (more than run at once: shifts change). */
  vehicles: number;
  /** Most vehicles running at once (in one poll). */
  peakVehicles: number;
  /** Vehicles in the latest poll of the window. */
  latestVehicles: number;
  latestPoll: string | null;
  meanDelaySeconds: number | null;
  first: string | null;
  last: string | null;
}

/** Vehicles and reports per minute of delay. fromMinutes null = under 1 min (incl. early); toMinutes null = 10+. */
export interface DelayBand {
  fromMinutes: number | null;
  toMinutes: number | null;
  /** Different vehicles with a report in this band; a vehicle can be in several bands. */
  vehicles: number;
  reports: number;
}

export interface RouteActivity {
  route: string;
  samples: number;
}

/** One recorded position report. */
export interface PositionSample {
  observedAt: string;
  vehicleId: number;
  vehicleNumber: string;
  routeName: string | null;
  brigade: string | null;
  headsign: string | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  delaySeconds: number | null;
  nextStopName: string | null;
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

export interface User {
  id: number;
  username: string;
  role: string;
  createdAt: string;
}

export interface UserRole {
  id: number;
  name: string;
}

/** Request payload type: entity without its id (plus write-only fields where needed). */
export type Payload<T> = Omit<T, 'id'>;
export type UserPayload = { username: string; password?: string; role: string };

export interface Session {
  token: string;
  username: string;
  role: RoleName;
}
