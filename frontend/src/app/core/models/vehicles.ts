/** Vehicles and their live positions. */
import { RouteSubtype } from './timetable';

export interface Vehicle {
  id: number;
  vehicleNumber: string;
  type: string;
  capacity: number | null;
  agency: string | null;
  model: string | null;
  /** Size class from the model; null when the model isn't known. */
  vehicleClass: VehicleClass | null;
  /** Who runs it: KPKM, KPK or KZK for BKM, else its agency; null when not known. */
  operatingAgency: string | null;
}

/** Bus size: mini (under 8 m), midi (8–11 m), maxi (11–15 m), mega (15 m+, articulated) or a double-decker. */
export type VehicleClass = 'mini' | 'midi' | 'maxi' | 'mega' | 'double_decker';

/** Live position from an external feed (zbiorkom.live); not stored in the backend. */
export interface LivePosition {
  /** Matching row in the vehicle table; null if it could not be registered yet. */
  vehicleId: number | null;
  vehicleNumber: string;
  type: string;
  agency: string | null;
  model: string | null;
  routeName: string | null;
  brigade: string | null;
  headsign: string | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  /** Negative when early. */
  delaySeconds: number | null;
  timestamp: string | null;
  upcomingStops: UpcomingStop[];
  /** Kind of route; null when the vehicle reports no route. */
  subtype: RouteSubtype | null;
}

/** A brigade's (vehicle duty's) runs on the current service day, from the timetable. */
export interface BrigadeSchedule {
  brigade: string;
  /** yyyy-MM-dd; runs after midnight belong to the day before. */
  serviceDay: string;
  /** In departure order; empty when the brigade is not in today's timetable. */
  runs: ScheduledRun[];
}

export interface ScheduledRun {
  route: string;
  headsign: string | null;
  from: string;
  to: string;
  /** ISO instants. */
  departure: string;
  arrival: string;
}

export interface UpcomingStop {
  sequence: number | null;
  name: string;
  scheduledArrival: string | null;
  scheduledDeparture: string | null;
}
