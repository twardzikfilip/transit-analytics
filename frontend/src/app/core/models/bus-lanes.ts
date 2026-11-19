/** Bus lanes from OpenStreetMap (GET /api/bus-lanes), and speeds and delays on them (GET /api/bus-lanes/report). */

/** The length of bus lanes the city gives. */
export interface OfficialLength {
  km: number;
  date: string;
  source: string;
  url: string;
}

/** A street section with a bus lane: `forward` lanes in the direction of `points`, `backward` against it. */
export interface BusLane {
  id: number;
  name: string | null;
  forward: number;
  backward: number;
  /**
   * Buses have a lane the other way too: on this section (forward and backward), or on the other carriageway of a
   * dual carriageway running alongside.
   */
  bothWays: boolean;
  points: [number, number][];
}

export type LaneHeading = 'northbound' | 'eastbound' | 'southbound' | 'westbound';

/** A street's bus lanes in one direction of travel. */
export interface BusLaneStretch {
  id: number;
  name: string;
  heading: LaneHeading;
  km: number;
  /** Its sections, [lat, lon] in the direction of travel. */
  paths: [number, number][][];
}

export interface BusLanes {
  source: string;
  fetchedAt: string;
  /** Length of bus lane, each direction's lane counted. */
  laneKm: number;
  sections: number;
  official: OfficialLength | null;
  lanes: BusLane[];
  stretches: BusLaneStretch[];
}

/**
 * Speeds (km/h, stops included) and delay change (seconds gained per km; negative: made up) of some buses'
 * movements. Peak and off-peak: weekdays.
 */
export interface LaneFigures {
  /** km driven (recorded). */
  km: number;
  speed: number | null;
  peakSpeed: number | null;
  offPeakSpeed: number | null;
  delayPerKm: number | null;
  peakDelayPerKm: number | null;
  offPeakDelayPerKm: number | null;
  /** How much slower at peak than off-peak, %. */
  peakSlowdown: number | null;
}

/** One weekday hour, on bus lanes and elsewhere. */
export interface LaneHour {
  hour: number;
  laneSpeed: number | null;
  otherSpeed: number | null;
  laneDelayPerKm: number | null;
  otherDelayPerKm: number | null;
  laneKm: number;
  otherKm: number;
}

export interface LaneStretchFigures {
  id: number;
  name: string;
  heading: LaneHeading;
  km: number;
  lines: string[];
  figures: LaneFigures;
  /** The same street the other way, where it has no bus lane; null when it has one there too. */
  opposite: LaneFigures | null;
}

export interface BusLaneReport {
  from: string;
  to: string;
  days: number;
  onLanes: LaneFigures;
  /** On the streets with bus lanes, in the direction without one. */
  sameStreetsWithoutLane: LaneFigures;
  /** Everywhere in the city off bus lanes. */
  elsewhere: LaneFigures;
  hours: LaneHour[];
  /** The most driven first. */
  stretches: LaneStretchFigures[];
}

/** How much of one direction of a line's main route runs on bus lanes. */
export interface LineBusLanes {
  direction: number;
  from: string;
  to: string;
  km: number;
  laneKm: number;
  percent: number;
  onLanes: [number, number][][];
}
