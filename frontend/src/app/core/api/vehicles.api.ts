import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BrigadeSchedule, LivePosition, Vehicle } from '../models/models';
import { CrudApiService } from './crud-api.service';

@Injectable({ providedIn: 'root' })
export class VehicleApi extends CrudApiService<Vehicle> {
  constructor() {
    super('/api/vehicles');
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

/** Brigades (vehicle duties) from the timetable. */
@Injectable({ providedIn: 'root' })
export class BrigadeApi {
  private readonly http = inject(HttpClient);

  /** The brigade's runs on the current service day. */
  schedule(brigade: string): Observable<BrigadeSchedule> {
    return this.http.get<BrigadeSchedule>(`/api/brigades/${encodeURIComponent(brigade)}/schedule`);
  }
}
