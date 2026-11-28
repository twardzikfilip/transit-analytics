import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DelayBand, FleetSeries, RouteActivity, StopDwell, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** What a position-history request covers: a window, and optionally one route and one operator (null for all). */
export interface HistoryFilter {
  route: string | null;
  agency: string | null;
}

/** Recorded vehicle positions (time series). Times are ISO-8601 instants; `to` is exclusive. */
@Injectable({ providedIn: 'root' })
export class PositionHistoryApi {
  private readonly http = inject(HttpClient);

  fleet(window: WindowQuery, filter: HistoryFilter): Observable<FleetSeries> {
    return this.http.get<FleetSeries>('/api/positions/fleet', { params: params(window, filter) });
  }

  delays(window: WindowQuery, filter: HistoryFilter): Observable<DelayBand[]> {
    return this.http.get<DelayBand[]>('/api/positions/delays', { params: params(window, filter) });
  }

  /** Routes of the operator recorded in the window. */
  routes(window: WindowQuery, agency: string | null): Observable<RouteActivity[]> {
    return this.http.get<RouteActivity[]>('/api/positions/routes', {
      params: params(window, { route: null, agency }),
    });
  }

  /** The stops with the longest mean dwell: one route's, or every route's (an operator's). */
  dwell(window: WindowQuery, filter: HistoryFilter): Observable<StopDwell[]> {
    return this.http.get<StopDwell[]>('/api/positions/dwell', { params: params(window, filter) });
  }

  /** Operators on the feed, the city operator first. */
  agencies(): Observable<string[]> {
    return this.http.get<string[]>('/api/positions/agencies');
  }
}

function params(window: WindowQuery, filter: HistoryFilter): Record<string, string | number> {
  const p: Record<string, string | number> = windowParams(window);
  if (filter.route) p['route'] = filter.route;
  if (filter.agency) p['agency'] = filter.agency;
  return p;
}
