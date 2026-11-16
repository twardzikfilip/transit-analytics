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

/**
 * Recorded positions as animation frames (GET /api/positions/replay). Positions are compact rows
 * [vehicleIndex, latitude, longitude, delaySeconds | null, lineIndex | null] into `vehicles` and `lines`.
 */
export interface ReplayResponse {
  from: string;
  to: string;
  stepSeconds: number;
  routes: string[];
  vehicles: string[];
  lines: string[];
  frames: { time: string; positions: [number, number, number, number | null, number | null][] }[];
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

/** Per-line statistics from the weekday timetable (GET /api/lines/stats). */
export interface LineStatsReport {
  /** Weekday service used, e.g. P_1349; null before a timetable is imported. */
  serviceId: string | null;
  validDays: number;
  firstDate: string | null;
  lastDate: string | null;
  peak: TimePeriod[];
  offPeak: TimePeriod;
  lines: LineStats[];
}

export interface TimePeriod {
  from: string;
  to: string;
}

export interface LineStats {
  routeId: number;
  route: string;
  color: string | null;
  textColor: string | null;
  /** Main route, averaged over directions. */
  lengthKm: number | null;
  shortestTravelSeconds: number | null;
  longestTravelSeconds: number | null;
  /** Average speed of main-route trips starting in the period (total distance / total time). */
  peakSpeedKmh: number | null;
  offPeakSpeedKmh: number | null;
  peakIntervalMinutes: number | null;
  offPeakIntervalMinutes: number | null;
  /** Coefficient of variation of the gaps between departures; 0 = perfectly even. */
  peakRegularityCv: number | null;
  offPeakRegularityCv: number | null;
  peakVehicles: number;
  offPeakVehicles: number;
  trips: number;
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

/** A line to pick on Routes in detail; `line` is the route's GTFS id. */
export interface LineRef {
  id: number;
  line: string;
  name: string;
  subtype: RouteSubtype;
}

/** Variant kind: the base route of its direction, longer / shorter than it, or mostly runs to or from the depot. */
export type VariantKind = 'main' | 'longer' | 'shorter' | 'depot';

export interface StopPoint {
  name: string;
  lat: number | null;
  lon: number | null;
  /** Distance along the route from its first stop. */
  km: number;
  zone: string | null;
}

export interface LineVariant {
  direction: number;
  shapeId: string | null;
  headsign: string | null;
  kind: VariantKind;
  km: number;
  tripsPerDay: number;
  shortestMin: number;
  longestMin: number;
  minSpeedKmh: number | null;
  maxSpeedKmh: number | null;
  firstStop: string | null;
  lastStop: string | null;
  /** Route shape as [lat, lon]. */
  points: [number, number][];
  stops: StopPoint[];
}

export interface LineBase {
  lengthKm: number | null;
  shortestMin: number | null;
  longestMin: number | null;
  minSpeedKmh: number | null;
  maxSpeedKmh: number | null;
}

/** The part of a direction's base route in fare zone 2 (outside the city). */
export interface LineZone2 {
  direction: number;
  headsign: string | null;
  kind: VariantKind;
  stops: number;
  totalStops: number;
  km: number;
  totalKm: number;
}

/** Times are seconds after midnight; they can pass 24:00. */
export interface BrigadeRun {
  from: number;
  to: number;
  direction: number;
  headsign: string | null;
  kind: VariantKind;
  /** Set when the run goes from the depot into service, or back. */
  depot: DepotKind | null;
}

export type DepotKind = 'pull_out' | 'pull_in';

/** Nearby stops (< 500 m) as one place, named after the stop where runs end; `alsoNames` are the others. */
export interface EndStop {
  name: string;
  alsoNames: string[];
}

/** A run between the depot area and the route. Times are seconds after midnight. */
export interface DepotRun {
  brigade: string;
  from: number;
  to: number;
  fromStop: string | null;
  toStop: string | null;
  direction: number;
  headsign: string | null;
  kind: DepotKind;
  km: number | null;
}

export interface Brigade {
  name: string;
  start: number;
  end: number;
  runs: BrigadeRun[];
  breaks: { from: number; to: number }[];
}

export interface LineDetail {
  id: number;
  line: string;
  name: string;
  subtype: RouteSubtype;
  serviceId: string | null;
  base: LineBase;
  variants: LineVariant[];
  zone2: LineZone2[];
  brigades: Brigade[];
  /** Where buses turn round in service; stops closer than 500 m are one end stop. */
  endStops: EndStop[];
  /** Where depot runs start or end, away from the end stops; grouped the same way. */
  depotStops: EndStop[];
  depotRuns: DepotRun[];
}

/** Hours of the service day: 3–23, then 24–26 after midnight. */
export interface HourStat {
  hour: number;
  runs: number;
  min: number;
  max: number;
  avg: number;
  median: number;
}

export interface DelaySlot {
  /** Start of the 15-minute slot, minutes after midnight (can pass 1440). */
  minute: number;
  avgDelaySeconds: number;
  samples: number;
}

export interface Segment {
  from: string;
  to: string;
  fromKm: number;
  toKm: number;
  kmh: number | null;
}

export interface SegmentProfile {
  direction: number;
  headsign: string | null;
  runs: number;
  segments: Segment[];
}

/** Measured from recorded positions on weekdays. */
export interface LinePerformance {
  firstDay: string | null;
  lastDay: string | null;
  weekdays: number;
  fullRuns: number;
  /** The base route first, then each longer variant. */
  travelTimes: TravelTimes[];
  delays: DelaySlot[];
  segments: SegmentProfile[];
  vehicles: LineVehicles;
}

/** Full runs of one route pattern (base route or a longer variant, both directions). */
export interface TravelTimes {
  kind: VariantKind;
  label: string;
  km: number;
  runs: number;
  scheduledShortestMin: number | null;
  scheduledLongestMin: number | null;
  scheduledMinSpeedKmh: number | null;
  scheduledMaxSpeedKmh: number | null;
  durations: HourStat[];
  speeds: HourStat[];
}

/** The buses that served a line, from every recorded day. */
export interface LineVehicles {
  days: number;
  vehicles: {
    number: string;
    model: string | null;
    days: number;
    brigades: string[];
    lastSeen: string;
  }[];
  brigades: {
    brigade: string;
    mostUsed: string;
    mostUsedDays: number;
    days: number;
    vehicles: number;
  }[];
  /** Brigades that changed bus during a day, most recent day first. */
  switches: {
    day: string;
    brigade: string;
    spells: { number: string; from: string; to: string }[];
  }[];
}

/** A stretch of 4+ consecutive stops along which every listed line runs (both directions where they run back). */
export interface Corridor {
  from: string;
  to: string;
  /** Stops of the busier direction, in order. */
  stops: string[];
  /** Every line running along it (at least half of it). */
  lines: string[];
  /** The lines running all of it; the others run part of it. */
  throughLines: string[];
  km: number | null;
  /** Weekday buses on its stretches (averaged along it), both directions. */
  buses: number;
  oneWay: boolean;
  peakIntervalMin: number | null;
  offPeakIntervalMin: number | null;
  peakCv: number | null;
  offPeakCv: number | null;
}

export interface CorridorReport {
  serviceId: string | null;
  peak: { from: string; to: string }[];
  offPeak: { from: string; to: string }[];
  corridors: Corridor[];
  /** Every stretch between two consecutive stops, quietest first. */
  segments: SegmentLoad[];
}

/** Weekday buses between two consecutive stops, both directions together. */
export interface SegmentLoad {
  from: string;
  to: string;
  /** Buses in the peak periods. */
  peakBuses: number;
  /** Buses all day. */
  buses: number;
  /** The route between the stops as [lat, lon]. */
  points: [number, number][];
}
