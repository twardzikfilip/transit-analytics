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
  WindowQuery,
} from '../models/models';
import { windowParams } from './window-params';

/** Recorded vehicle positions (time series). Times are ISO-8601 instants; `to` is exclusive. */
@Injectable({ providedIn: 'root' })
export class PositionHistoryApi {
  private readonly http = inject(HttpClient);

  fleet(window: WindowQuery, route: string | null): Observable<FleetSeries> {
    return this.http.get<FleetSeries>('/api/positions/fleet', {
      params: withRoute(windowParams(window), route),
    });
  }

  delays(window: WindowQuery, route: string | null): Observable<DelayBand[]> {
    return this.http.get<DelayBand[]>('/api/positions/delays', {
      params: withRoute(windowParams(window), route),
    });
  }

  /** Animation frames of recorded positions; `routes` empty = all lines. */
  replay(window: WindowQuery, routes: string[]): Observable<ReplayResponse> {
    let params = new HttpParams({ fromObject: windowParams(window) });
    for (const r of routes) params = params.append('route', r);
    return this.http.get<ReplayResponse>('/api/positions/replay', { params });
  }

  routes(window: WindowQuery): Observable<RouteActivity[]> {
    return this.http.get<RouteActivity[]>('/api/positions/routes', {
      params: windowParams(window),
    });
  }

  /** @param sort e.g. "delay,desc"; the backend sorts, since the reports are paged */
  samples(
    window: WindowQuery,
    route: string | null,
    page: number,
    size: number,
    sort = 'time,desc',
    excludeOutliers = false,
  ): Observable<Page<PositionSample>> {
    const params = withRoute({ ...windowParams(window), page, size, sort }, route);
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
