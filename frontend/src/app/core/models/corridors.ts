/** Important corridors and the stretch loads for their maps. */

/** A stretch of 4+ consecutive stops along which every listed route runs (both directions where they run back). */
export interface Corridor {
  from: string;
  to: string;
  /** Stops of the busier direction, in order. */
  stops: string[];
  /** Every route running along it (at least half of it). */
  routes: string[];
  /** The routes running all of it; the others run part of it. */
  throughRoutes: string[];
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
