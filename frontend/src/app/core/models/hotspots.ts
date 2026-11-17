/** Stop hotspots: where buses stand still away from stops, from recorded positions. */

export interface HotspotLineTime {
  line: string;
  hours: number;
}

/** A compass heading (N, NE, E, …) and its share of the time, 0–1. */
export interface HotspotApproach {
  heading: string;
  share: number;
}

/** A place where buses repeatedly stood still. */
export interface Hotspot {
  id: number;
  lat: number;
  lon: number;
  /** 90% of its standstills are this close to the centre. */
  radiusMeters: number;
  /** Time standing still there altogether. */
  hours: number;
  /** Time lost per recorded day. */
  minutesPerDay: number;
  standstills: number;
  vehicles: number;
  meanSeconds: number;
  /** A typical standstill. */
  medianSeconds: number;
  /** A long one (90th percentile). */
  p90Seconds: number;
  /** The nearest stop, to tell where it is. */
  nearestStop: string | null;
  nearestStopMeters: number;
  /** Time per line, the most first. */
  lines: HotspotLineTime[];
  /** Directions the buses were heading, by share of the time (10% or more). */
  approaches: HotspotApproach[];
  /** Hour of the day (local time) with the most time lost. */
  peakHour: number;
}

export interface HotspotReport {
  from: string;
  to: string;
  /** The line the report is limited to, or null for all. */
  line: string | null;
  /** Lines with standstills in the window, to choose from. */
  lines: string[];
  /** Service days with recorded standstills in the window; per-day figures are over these. */
  days: number;
  /** Standstills away from stops and terminals, and their time altogether. */
  standstills: number;
  standstillHours: number;
  /** The part of that time in the hotspots. */
  hotspotHours: number;
  /** The most time lost first. */
  hotspots: Hotspot[];
}
