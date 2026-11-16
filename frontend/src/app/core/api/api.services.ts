import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LivePosition, Route, Stop, Trip, User, UserPayload, UserRole, Vehicle, VehiclePosition } from '../models/models';
import { CrudApiService } from './crud-api.service';

@Injectable({ providedIn: 'root' })
export class VehicleApi extends CrudApiService<Vehicle> {
  constructor() {
    super('/api/vehicles');
  }
}

@Injectable({ providedIn: 'root' })
export class VehiclePositionApi extends CrudApiService<VehiclePosition, Omit<VehiclePosition, 'id' | 'vehicleNumber'>> {
  constructor() {
    super('/api/vehicle-positions');
  }

  /** Latest position of every vehicle. */
  latest(): Observable<VehiclePosition[]> {
    return this.http.get<VehiclePosition[]>(`${this.baseUrl}/latest`);
  }
}

@Injectable({ providedIn: 'root' })
export class RouteApi extends CrudApiService<Route> {
  constructor() {
    super('/api/routes');
  }
}

@Injectable({ providedIn: 'root' })
export class StopApi extends CrudApiService<Stop> {
  constructor() {
    super('/api/stops');
  }
}

@Injectable({ providedIn: 'root' })
export class TripApi extends CrudApiService<Trip> {
  constructor() {
    super('/api/trips');
  }
}

@Injectable({ providedIn: 'root' })
export class UserApi extends CrudApiService<User, UserPayload> {
  constructor() {
    super('/api/users');
  }
}

@Injectable({ providedIn: 'root' })
export class UserRoleApi extends CrudApiService<UserRole> {
  constructor() {
    super('/api/roles');
  }
}

/** Live data from zbiorkom.live, passed through by the backend. */
@Injectable({ providedIn: 'root' })
export class ZbiorkomApi {
  private readonly http = inject(HttpClient);

  bialystokPositions(): Observable<LivePosition[]> {
    return this.http.get<LivePosition[]>('/api/zbiorkom/bialystok/positions');
  }
}
