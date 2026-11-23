import { BrigadeTrip } from './brigades';
import { WindowQuery } from './common';

/** Trips scheduled in a time window, each with what ran (GET /api/trip-runs). */
export interface TripRunPage {
  from: string;
  to: string;
  /** The start of what is listed: the window's, or 24 h before its end when the window is longer. */
  shownFrom: string;
  q: string | null;
  /** Trips in the window matching the search; `trips` is page `page` (from 0) of them, `size` a page. */
  total: number;
  page: number;
  size: number;
  trips: TripRun[];
}

/** A scheduled trip and how it ran. */
export interface TripRun {
  /** yyyy-MM-dd; trips after midnight belong to the day before. */
  serviceDay: string;
  brigade: string;
  trip: BrigadeTrip;
  /** The bus's delay and speed while it ran the trip, about every minute, in time order. */
  samples: TripSample[];
  /** Where a bus stood still on the way for 3 min or more. */
  stands: TripStand[];
  /** The trip's stops in order, when each was due and when a bus passed it. */
  stops: TripStopTime[];
  /** Its buses' average speed over the trip, stops and waits included; null when not seen running. */
  averageKmh: number | null;
}

export interface TripStopTime {
  /** Its number on the trip (1-based). */
  index: number;
  name: string;
  /** When the bus was due to leave it. */
  scheduled: string;
  /** When a bus was first seen heading beyond it (the last stop: its arrival); null when not seen. */
  passed: string | null;
  /** The delay then (seconds); null when unknown. */
  delay: number | null;
}

export interface TripSample {
  at: string;
  bus: string;
  /** Seconds late (negative: early); null when the feed gave none. */
  delay: number | null;
  /** Speed since the sample before; null for the first one or a GPS jump. */
  kmh: number | null;
  /** The stop it was heading to (1-based). */
  stop: number;
}

export interface TripStand {
  bus: string;
  from: string;
  to: string;
  minutes: number;
  /** The stop it was heading to, and its number (1-based). */
  stop: string;
  stopIndex: number;
  /** How much later it was when it moved on (seconds); null when unknown. */
  delayGrowth: number | null;
  lat: number;
  lon: number;
}

/** What the Trips page asks for. */
export interface TripRunQuery {
  window: WindowQuery;
  q: string | null;
  page: number;
}

/**
 * One trip in detail, asked for when it is opened (GET /api/trip-runs/{date}/{brigade}/{trip}): every report of its
 * buses while they ran it, its route, and its stops with how long the bus stood at each.
 */
export interface TripRunDetail {
  tripId: string;
  /** Every report while a bus ran its part, in time order. */
  points: TripDetailPoint[];
  /** The route shape, [lat, lon]; empty when the timetable has none. */
  route: [number, number][];
  /** The trip's stops in order. */
  stops: TripDetailStop[];
}

export interface TripDetailPoint {
  at: string;
  bus: string;
  lat: number;
  lon: number;
  /** Seconds late (negative: early); null when the feed gave none. */
  delay: number | null;
  /** 0 standing still; else over the half minute around it; null for a bus's first report, after a gap or a jump. */
  kmh: number | null;
}

export interface TripDetailStop {
  /** Its number on the trip (1-based). */
  index: number;
  name: string;
  lat: number;
  lon: number;
  /** How long a bus stood within 30 m of it (s); 0 went past without stopping; null not seen near it. */
  dwellSeconds: number | null;
}
