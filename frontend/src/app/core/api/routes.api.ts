import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RouteDetail, RoutePerformance, RouteRef, RouteStatsReport } from '../models/models';

/** Per-route statistics from the weekday timetable. */
@Injectable({ providedIn: 'root' })
export class RouteStatsApi {
  private readonly http = inject(HttpClient);

  weekday(): Observable<RouteStatsReport> {
    return this.http.get<RouteStatsReport>('/api/routes/stats');
  }
}

@Injectable({ providedIn: 'root' })
export class RouteDetailApi {
  private readonly http = inject(HttpClient);

  /** Every route, the timetable's and those only seen live, with its kind. */
  routes(): Observable<RouteRef[]> {
    return this.http.get<RouteRef[]>('/api/routes');
  }

  detail(route: string): Observable<RouteDetail> {
    return this.http.get<RouteDetail>(`/api/routes/${encodeURIComponent(route)}/detail`);
  }

  performance(route: string): Observable<RoutePerformance> {
    return this.http.get<RoutePerformance>(`/api/routes/${encodeURIComponent(route)}/performance`);
  }
}
