import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  LineDetail,
  LinePerformance,
  LineRef,
  LineStatsReport,
  RouteSubtype,
} from '../models/models';

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

  /** Every line of the timetable by name, with its kind. */
  subtypes(): Observable<Record<string, RouteSubtype>> {
    return this.http.get<Record<string, RouteSubtype>>('/api/routes/subtypes');
  }

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
