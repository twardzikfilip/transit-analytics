import { LineBusLanes } from './bus-lanes';
import { RouteCapacity, RouteGeometry, RouteSubtype } from './timetable';

/** Routes in detail: one line's timetable and its measured performance. */

/**
 * A line to pick on Routes in detail. `line` is the route's GTFS id, used in the URLs; for a line only seen on the
 * live feed (another operator's, not in the timetable) it is the line's name, and `id` is null.
 */
export interface LineRef {
  id: number | null;
  line: string;
  name: string;
  subtype: RouteSubtype;
  /** Null for lines not classified (other operators'). */
  geometry: RouteGeometry | null;
  capacityClass: RouteCapacity | null;
  /** The company operating the line for BKM: KPKM, KPK or KZK; null when not known (other operators' lines). */
  operatingAgency: string | null;
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
  /** The route's id (for its trips); null for a line not in the timetable. */
  id: number | null;
  line: string;
  name: string;
  subtype: RouteSubtype;
  geometry: RouteGeometry | null;
  capacityClass: RouteCapacity | null;
  /** Null when there is no timetable for the line. */
  serviceId: string | null;
  /** The line's timetable on the operator's website. */
  url: string | null;
  base: LineBase | null;
  variants: LineVariant[];
  zone2: LineZone2[];
  brigades: Brigade[];
  /** Where buses turn round in service; stops closer than 500 m are one end stop. */
  endStops: EndStop[];
  /** Where depot runs start or end, away from the end stops; grouped the same way. */
  depotStops: EndStop[];
  depotRuns: DepotRun[];
  /** Who runs the line: the organiser and the operating company with its depot; null when not known. */
  operator: LineOperator | null;
  /** Every bus depot. */
  depots: LineDepot[];
  /** Where the line's buses enter service (pull_out) and leave it (pull_in), with the way from or to the depot. */
  depotLinks: DepotLink[];
  /** How much of each direction's main route runs on bus lanes. */
  busLanes: LineBusLanes[];
  /** For a line without a timetable: where its buses went, from their recorded positions; null otherwise. */
  recorded: RecordedRoutes | null;
  /**
   * Whether the timetable says which brigade runs each trip. Another operator's may not: then there are no brigades,
   * depot runs or depot-run stops to show.
   */
  brigadesKnown: boolean;
}

/** Where a line's buses went, from all their recorded positions: its variants, the most driven first. */
export interface RecordedRoutes {
  /** Null when nothing usable was recorded. */
  first: string | null;
  last: string | null;
  /** Runs found: a bus's reports in a row towards one destination. */
  runs: number;
  variants: RecordedVariant[];
}

/** A way the line's buses went: towards one destination along the same streets. */
export interface RecordedVariant {
  id: number;
  headsign: string;
  /** The stop buses were first and last heading to, most often; null when not reported. */
  from: string | null;
  to: string | null;
  /** How many times it was driven. */
  runs: number;
  km: number;
  lastSeen: string;
  /** [lat, lon] in the direction of travel. */
  path: [number, number][];
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

/**
 * Who runs a line: for BKM's lines the organiser and one of its companies (code KPKM, KPK or KZK) with its depot; for
 * another operator's own timetable (e.g. PKS Nova) just that operator, with no code or depot.
 */
export interface LineOperator {
  organiser: string;
  code: string | null;
  name: string;
  website: string | null;
  depot: string | null;
  depotAddress: string | null;
}

/** A bus depot: the company whose it is, and its area (closed rings of [lat, lon]). */
export interface LineDepot {
  code: string;
  name: string;
  address: string | null;
  outlines: [number, number][][];
}

/**
 * Where brigades start their day (pull_out: the first stop of their first trip) or end it (pull_in), with the way
 * between there and the depot: along the streets (`routed`) or, when that couldn't be found, straight.
 */
export interface DepotLink {
  kind: 'pull_out' | 'pull_in';
  stop: string;
  lat: number;
  lon: number;
  /** The depot's code. */
  depot: string;
  brigades: number;
  path: [number, number][];
  routed: boolean;
}
