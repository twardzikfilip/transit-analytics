import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { FleetReport, LivePosition, VehicleDetail, WindowQuery } from '../models/models';
import { BACKGROUND } from './activity';
import { windowParams } from './window-params';

/** Live data from zbiorkom.live, passed through by the backend. */
@Injectable({ providedIn: 'root' })
export class ZbiorkomApi {
  private readonly http = inject(HttpClient);

  bialystokPositions(): Observable<LivePosition[]> {
    // Polled every few seconds: in the background, without the progress bar.
    return this.http.get<LivePosition[]>('/api/zbiorkom/bialystok/positions', {
      context: new HttpContext().set(BACKGROUND, true),
    });
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

  /** One bus's number, e.g. "230" (for its page's title). */
  number(id: number): Observable<string> {
    return this.http
      .get<{ number: string }>(`/api/fleet/vehicles/${id}/info`)
      .pipe(map((v) => v.number));
  }

  /** One bus in a window: its runs per day and the abnormal trips it was involved in. */
  vehicle(id: number, window: WindowQuery): Observable<VehicleDetail> {
    return this.http.get<VehicleDetail>(`/api/fleet/vehicles/${id}`, {
      params: windowParams(window),
    });
  }
}
