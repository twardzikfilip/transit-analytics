import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BrigadeSchedule,
  FleetReport,
  LivePosition,
  VehicleDetail,
  WindowQuery,
} from '../models/models';
import { windowParams } from './window-params';

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

/** The fleet: how its buses were used in a time window. */
@Injectable({ providedIn: 'root' })
export class FleetApi {
  private readonly http = inject(HttpClient);

  /** The fleet in a window, for the given operators' buses (all when none are given). */
  report(window: WindowQuery, agencies: string[] = []): Observable<FleetReport> {
    return this.http.get<FleetReport>('/api/fleet', {
      params: { ...windowParams(window), agency: agencies },
    });
  }

  /** One bus in a window: its runs per day and the abnormal trips it was involved in. */
  vehicle(id: number, window: WindowQuery): Observable<VehicleDetail> {
    return this.http.get<VehicleDetail>(`/api/fleet/vehicles/${id}`, { params: windowParams(window) });
  }
}
