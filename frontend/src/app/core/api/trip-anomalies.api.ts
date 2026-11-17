import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TripAnomalyReport, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** Abnormal trips: scheduled trips not run, run only partly, or off their route. */
@Injectable({ providedIn: 'root' })
export class TripAnomalyApi {
  private readonly http = inject(HttpClient);

  /** The trips scheduled to run (for any part) in the window. */
  report(window: WindowQuery): Observable<TripAnomalyReport> {
    return this.http.get<TripAnomalyReport>('/api/trip-anomalies', {
      params: windowParams(window),
    });
  }
}
