import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TripRun, TripRunDetail, TripRunPage, TripRunQuery } from '../models/models';
import { windowParams } from './window-params';

/** Trips scheduled in a time window and how they ran. */
@Injectable({ providedIn: 'root' })
export class TripRunApi {
  private readonly http = inject(HttpClient);

  page(query: TripRunQuery): Observable<TripRunPage> {
    const params: Record<string, string> = { ...windowParams(query.window), page: `${query.page}` };
    if (query.q) params['q'] = query.q;
    return this.http.get<TripRunPage>('/api/trip-runs', { params });
  }

  /** One trip in detail: every report with delay and speed, its route, and its stops with how long the bus stood. */
  detail(run: TripRun): Observable<TripRunDetail> {
    const parts = [run.serviceDay, run.brigade, run.trip.tripId].map(encodeURIComponent);
    return this.http.get<TripRunDetail>(`/api/trip-runs/${parts.join('/')}`);
  }
}
