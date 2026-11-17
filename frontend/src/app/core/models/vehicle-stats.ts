/** Vehicles in detail: how the vehicles were used, from recorded positions. */

export type StatsWindow = 'day' | 'week' | 'month';

export interface StatsVehicle {
  id: number;
  number: string;
  model: string | null;
  agency: string | null;
}

/** An operator and how many of its vehicles served a line in the window. */
export interface StatsAgency {
  name: string;
  vehicles: number;
}

export interface VehicleUsage {
  vehicle: StatsVehicle;
  /** In service: each report counts until the vehicle's next one, up to a minute. */
  hours: number;
  /** Service days on which it served a line. */
  days: number;
  /** Different lines served, in line order. */
  lines: string[];
  firstSeen: string;
  lastSeen: string;
}

/** One vehicle's lines on one service day, in the order served. */
export interface VehicleLineDay {
  /** yyyy-MM-dd */
  day: string;
  vehicle: StatsVehicle;
  lines: { line: string; brigade: string; from: string; to: string; hours: number }[];
}

/** A vehicle that stopped serving its brigade while it was scheduled to run. */
export interface VehicleIncident {
  day: string;
  vehicle: StatsVehicle;
  /** switched: seen later that day on another line; disappeared: not seen again that day. */
  kind: 'switched' | 'disappeared';
  line: string;
  brigade: string;
  lastSeen: string;
  lastStop: string | null;
  delaySeconds: number | null;
  /** End of the block of runs it left (the next break, or the brigade's day end). */
  scheduledUntil: string;
  scheduledEnd: string;
  missedRuns: number;
  nextLine: string | null;
  nextBrigade: string | null;
  nextSeen: string | null;
  replacedBy: StatsVehicle | null;
  replacedAt: string | null;
}

/** A bus of the fleet that didn't serve any line in the window. */
export interface IdleVehicle {
  vehicle: StatsVehicle;
  /** When it last served a line before, null if never recorded. */
  lastSeen: string | null;
  lastLine: string | null;
}

export interface VehicleStatsReport {
  window: StatsWindow;
  from: string;
  to: string;
  /** Vehicles that served a line in the window (of the chosen operators). */
  vehicles: number;
  /** All operators with vehicles in service in the window, to choose from. */
  agencies: StatsAgency[];
  mostUsed: VehicleUsage[];
  leastUsed: VehicleUsage[];
  mostLines: VehicleUsage[];
  fewestLines: VehicleUsage[];
  multiLineDays: VehicleLineDay[];
  incidents: VehicleIncident[];
  /** The chosen operators' buses not in service at all in the window, idle the longest first. */
  unused: IdleVehicle[];
}
