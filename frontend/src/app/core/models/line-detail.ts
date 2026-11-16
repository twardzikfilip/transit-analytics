import { RouteSubtype } from './timetable';

/** Routes in detail: one line's timetable and its measured performance. */

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
