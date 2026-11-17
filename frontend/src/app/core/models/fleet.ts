/** The fleet: how its buses were used in a time window, and one bus in detail, from recorded positions. */

export interface FleetVehicle {
  id: number;
  number: string;
  model: string | null;
  agency: string | null;
}

/** An operator and how many buses of the fleet are its. */
export interface FleetAgency {
  name: string;
  vehicles: number;
}

/** A model ("unknown" when the feed doesn't say): buses in use in the window, of the fleet's. */
export interface ModelCount {
  model: string;
  inUse: number;
  fleet: number;
}

export interface VehicleUsage {
  vehicle: FleetVehicle;
  /** In service: each report on a line counts until the bus's next one, up to a minute. */
  hours: number;
  /** Service days on which it served a line. */
  days: number;
  /** The lines served, in line order. */
  lines: string[];
}

/** A bus replaced midway through a trip and not used again that day (as on Abnormal trips). */
export interface Breakdown {
  vehicle: FleetVehicle;
  /** When it stopped for good. */
  at: string;
  line: string;
  brigade: string;
  headsign: string | null;
  /** The stop it was heading to. */
  stop: string | null;
  /** Its first report on a line after that day; null while it hasn't returned. */
  returned: string | null;
  /** Days until it returned, or until now. */
  daysOut: number;
}

export interface FleetReport {
  from: string;
  to: string;
  /** Buses of the chosen operators that served a line in the window. */
  inUse: number;
  /** Buses of the chosen operators: every bus ever seen on the live feed. */
  fleet: number;
  agencies: FleetAgency[];
  models: ModelCount[];
  mostUsed: VehicleUsage[];
  leastUsed: VehicleUsage[];
  /** Every bus, the longest in service first; those not used have 0 hours. */
  usage: VehicleUsage[];
  /** The latest first. */
  breakdowns: Breakdown[];
}

/** A run: consecutive reports on one line, brigade and headsign; seconds after the day's start. */
export interface VehicleRun {
  from: number;
  to: number;
  line: string;
  brigade: string | null;
  headsign: string | null;
}

export interface VehicleDay {
  /** yyyy-MM-dd: the service day (03:00 to 03:00). */
  day: string;
  /** Midnight before the day's 03:00 start: what run times count from (25:10 is 01:10 the next night). */
  start: string;
  hours: number;
  runs: VehicleRun[];
  /** Gaps of 30 minutes or more between runs. */
  breaks: { from: number; to: number }[];
}

/** An abnormal trip the bus was involved in. */
export interface VehicleIncident {
  /** vehicle-changed: it broke down (replaced, not used again that day); missed: its brigade's trip not run. */
  kind: 'vehicle-changed' | 'missed';
  day: string;
  line: string;
  brigade: string;
  headsign: string | null;
  scheduledDeparture: string;
  note: string;
}

export interface VehicleDetail {
  vehicle: FleetVehicle;
  from: string;
  to: string;
  hours: number;
  lines: string[];
  /** Its last report on a line before the end of the window; null if never. */
  lastSeen: string | null;
  days: VehicleDay[];
  incidents: VehicleIncident[];
}
