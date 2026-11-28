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

/** Vehicles and reports per delay band: on time (fromMinutes null: under 2 min, incl. early), 2–5, 5–10, 10+ (toMinutes null). */
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
  /** Its reports' mean delay, outliers left out; null when none had a delay. */
  meanDelaySeconds: number | null;
  /** The reports that mean is over. */
  delaySamples: number;
}

/** A stop where buses dwell long: the mean of its visits in the window (passing without halting counts 0). */
export interface StopDwell {
  stop: string;
  meanSeconds: number;
  visits: number;
}
