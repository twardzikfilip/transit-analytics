import { IncidentSpan, Phrase } from './trip-anomalies';
import { VehicleClass } from './vehicles';
/** The fleet: how its buses were used in a time window, and one bus in detail, from recorded positions. */

export interface FleetVehicle {
  id: number;
  number: string;
  model: string | null;
  agency: string | null;
  /** Size class from the model; null when not known. */
  vehicleClass: VehicleClass | null;
  /** Who runs it: KPKM, KPK or KZK for BKM, else its agency; null when not known. */
  operatingAgency: string | null;
}

/** An operator and how many buses of the fleet are its. */
export interface FleetAgency {
  name: string;
  vehicles: number;
}

/**
 * A model ("unknown" when the feed doesn't say): buses in use in the window, of the fleet's. Size classes and makes
 * are counted the same way, `model` naming the class or the make.
 */
export interface ModelCount {
  model: string;
  inUse: number;
  fleet: number;
}

export interface VehicleUsage {
  vehicle: FleetVehicle;
  /** In service: each report on a route counts until the bus's next one, up to a minute. */
  hours: number;
  /** Service days on which it served a route. */
  days: number;
  /** The routes served, in route order. */
  routes: string[];
}

/** A bus replaced midway through a trip and not used again that day (as on Abnormal trips). */
export interface BusChange {
  vehicle: FleetVehicle;
  /** When it stopped for good. */
  at: string;
  route: string;
  brigade: string;
  headsign: string | null;
  /** The stop it was heading to. */
  stop: string | null;
  /** Its first report on a route after that day; null while it hasn't returned. */
  returned: string | null;
  /** Whole hours until it returned, or until now. */
  hoursOut: number;
}

export interface FleetReport {
  from: string;
  to: string;
  /** Buses of the chosen operators that served a route in the window. */
  inUse: number;
  /** Buses of the chosen operators: every bus ever seen on the live feed. */
  fleet: number;
  agencies: FleetAgency[];
  models: ModelCount[];
  /** Per size class (mini, midi, maxi, mega, double_decker, or unknown), smallest first. */
  classes: ModelCount[];
  /** Per make, the model name's first word ("Solaris"), the most in use first. */
  makes: ModelCount[];
  mostUsed: VehicleUsage[];
  leastUsed: VehicleUsage[];
  /** Every bus, the longest in service first; those not used have 0 hours. */
  usage: VehicleUsage[];
  /** The latest first. */
  busChanges: BusChange[];
}

/** A run: consecutive reports on one route, brigade and headsign; seconds after the day's start. */
export interface VehicleRun {
  from: number;
  to: number;
  route: string;
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
  /** vehicle-changed: it was replaced and not used again that day; missed: its brigade's trip not run. */
  kind: 'vehicle-changed' | 'missed';
  day: string;
  route: string;
  brigade: string;
  headsign: string | null;
  scheduledDeparture: string;
  note: Phrase[];
  /** On a bus change: what each bus (it and its replacement) did when. */
  timeline: IncidentSpan[];
  /** On a bus change: where each of those buses went over the time line, [lat, lon] in order. */
  paths: { vehicle: string; points: [number, number][] }[];
}

export interface VehicleDetail {
  vehicle: FleetVehicle;
  from: string;
  to: string;
  hours: number;
  routes: string[];
  /** Its last report on a route before the end of the window; null if never. */
  lastSeen: string | null;
  days: VehicleDay[];
  incidents: VehicleIncident[];
}
