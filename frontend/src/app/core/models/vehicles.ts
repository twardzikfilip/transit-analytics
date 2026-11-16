/** Vehicles and their live positions. */

export interface Vehicle {
  id: number;
  vehicleNumber: string;
  type: string;
  capacity: number | null;
  agency: string | null;
  model: string | null;
}

/** Live position from an external feed (zbiorkom.live); not stored in the backend. */
export interface LivePosition {
  /** Matching row in the vehicle table; null if it could not be registered yet. */
  vehicleId: number | null;
  vehicleNumber: string;
  type: string;
  agency: string | null;
  model: string | null;
  routeName: string | null;
  routeColor: string | null;
  brigade: string | null;
  headsign: string | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  /** Negative when early. */
  delaySeconds: number | null;
  timestamp: string | null;
  upcomingStops: UpcomingStop[];
}

export interface UpcomingStop {
  sequence: number | null;
  name: string;
  scheduledArrival: string | null;
  scheduledDeparture: string | null;
}
