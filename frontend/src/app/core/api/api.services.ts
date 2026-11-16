import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ReplayResponse,
  DelayBand,
  FleetSeries,
  LineStatsReport,
  CorridorReport,
  LineDetail,
  LinePerformance,
  LineRef,
  GtfsImportResult,
  LivePosition,
  Page,
  PositionSample,
  RouteActivity,
  Route,
  Stop,
  StopTime,
  Trip,
  User,
  UserPayload,
  UserRole,
  Vehicle,
} from '../models/models';
import { CrudApiService } from './crud-api.service';

@Injectable({ providedIn: 'root' })
export class VehicleApi extends CrudApiService<Vehicle> {
  constructor() {
    super('/api/vehicles');
  }
}

@Injectable({ providedIn: 'root' })
export class RouteApi extends CrudApiService<Route, unknown> {
  constructor() {
    super('/api/routes');
  }
}

@Injectable({ providedIn: 'root' })
export class StopApi extends CrudApiService<Stop, unknown> {
  constructor() {
    super('/api/stops');
  }
}

@Injectable({ providedIn: 'root' })
export class TripApi extends CrudApiService<Trip, unknown> {
  constructor() {
    super('/api/trips');
  }
}

@Injectable({ providedIn: 'root' })
export class StopTimeApi extends CrudApiService<StopTime, unknown> {
  constructor() {
    super('/api/stop-times');
  }
}

/** Imports public transport timetables (GTFS). */
@Injectable({ providedIn: 'root' })
export class GtfsApi {
  private readonly http = inject(HttpClient);

  importBialystok(): Observable<GtfsImportResult> {
    return this.http.post<GtfsImportResult>('/api/gtfs/bialystok/import', null);
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

/** Per-line statistics from the weekday timetable. */
@Injectable({ providedIn: 'root' })
export class LineStatsApi {
  private readonly http = inject(HttpClient);

  weekday(): Observable<LineStatsReport> {
    return this.http.get<LineStatsReport>('/api/lines/stats');
  }
}

@Injectable({ providedIn: 'root' })
export class LineDetailApi {
  private readonly http = inject(HttpClient);

  lines(): Observable<LineRef[]> {
    return this.http.get<LineRef[]>('/api/lines');
  }

  detail(line: string): Observable<LineDetail> {
    return this.http.get<LineDetail>(`/api/lines/${encodeURIComponent(line)}/detail`);
  }

  performance(line: string): Observable<LinePerformance> {
    return this.http.get<LinePerformance>(`/api/lines/${encodeURIComponent(line)}/performance`);
  }
}

@Injectable({ providedIn: 'root' })
export class CorridorApi {
  private readonly http = inject(HttpClient);

  report(): Observable<CorridorReport> {
    return this.http.get<CorridorReport>('/api/corridors');
  }
}
