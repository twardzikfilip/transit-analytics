/** Recorded vehicle positions: fleet over time, delays, replay frames and raw reports. */

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
 * [vehicleIndex, latitude, longitude, delaySeconds | null, lineIndex | null, brigadeIndex | null] into `vehicles`,
 * `lines` and `brigades`.
 */
export interface ReplayResponse {
  from: string;
  to: string;
  stepSeconds: number;
  routes: string[];
  vehicles: string[];
  lines: string[];
  brigades: string[];
  frames: {
    time: string;
    positions: [number, number, number, number | null, number | null, number | null][];
  }[];
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
