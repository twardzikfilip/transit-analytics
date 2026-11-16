export type RoleName = 'ADMIN' | 'GUEST';

export interface Page<T> {
  content: T[];
  page: { size: number; number: number; totalElements: number; totalPages: number };
}

export interface Vehicle {
  id: number;
  vehicleNumber: string;
  type: string;
  capacity: number | null;
  agency: string | null;
  model: string | null;
}

export interface VehiclePosition {
  id: number;
  vehicleId: number;
  vehicleNumber: string;
  tripId: number | null;
  latitude: number;
  longitude: number;
  bearing: number | null;
  speed: number | null;
  recordedAt: string;
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

export interface Route {
  id: number;
  shortName: string;
  longName: string | null;
  type: string;
  color: string | null;
}

export interface Stop {
  id: number;
  code: string | null;
  name: string;
  latitude: number;
  longitude: number;
}

export interface Trip {
  id: number;
  routeId: number;
  vehicleId: number | null;
  headsign: string | null;
  direction: number | null;
  startTime: string | null;
  endTime: string | null;
}

export interface User {
  id: number;
  username: string;
  role: string;
  createdAt: string;
}

export interface UserRole {
  id: number;
  name: string;
}

/** Request payload type: entity without its id (plus write-only fields where needed). */
export type Payload<T> = Omit<T, 'id'>;
export type UserPayload = { username: string; password?: string; role: string };

export interface Session {
  token: string;
  username: string;
  role: RoleName;
}
