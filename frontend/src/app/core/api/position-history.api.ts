import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DelayBand,
  FleetSeries,
  Page,
  PositionSample,
  ReplayResponse,
  RouteActivity,
} from '../models/models';

/** Recorded vehicle positions (time series). Times are ISO-8601 instants; `to` is exclusive. */
@Injectable({ providedIn: 'root' })
export class PositionHistoryApi {
  private readonly http = inject(HttpClient);

  fleet(from: string, to: string, route: string | null): Observable<FleetSeries> {
    return this.http.get<FleetSeries>('/api/positions/fleet', {
      params: withRoute({ from, to }, route),
    });
  }

  delays(from: string, to: string, route: string | null): Observable<DelayBand[]> {
    return this.http.get<DelayBand[]>('/api/positions/delays', {
      params: withRoute({ from, to }, route),
    });
  }

  /** Animation frames of recorded positions; `routes` empty = all lines. */
  replay(from: string, to: string, routes: string[]): Observable<ReplayResponse> {
    let params = new HttpParams().set('from', from).set('to', to);
    for (const r of routes) params = params.append('route', r);
    return this.http.get<ReplayResponse>('/api/positions/replay', { params });
  }

  routes(from: string, to: string): Observable<RouteActivity[]> {
    return this.http.get<RouteActivity[]>('/api/positions/routes', { params: { from, to } });
  }

  /** @param sort e.g. "delay,desc"; the backend sorts, since the reports are paged */
  samples(
    from: string,
    to: string,
    route: string | null,
    page: number,
    size: number,
    sort = 'time,desc',
    excludeOutliers = false,
  ): Observable<Page<PositionSample>> {
    const params = withRoute({ from, to, page, size, sort }, route);
    return this.http.get<Page<PositionSample>>('/api/positions', {
      params: excludeOutliers ? { ...params, excludeOutliers: 'true' } : params,
    });
  }
}

function withRoute(
  params: Record<string, string | number>,
  route: string | null,
): Record<string, string | number> {
  return route ? { ...params, route } : params;
}
