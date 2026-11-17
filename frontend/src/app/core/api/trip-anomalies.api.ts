import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TripAnomalyReport, TripWindow } from '../models/models';

/** Abnormal trips: scheduled trips not run, run only partly, or off their route. */
@Injectable({ providedIn: 'root' })
export class TripAnomalyApi {
  private readonly http = inject(HttpClient);

  /** The trips scheduled to leave in the window ending now. */
  report(window: TripWindow): Observable<TripAnomalyReport> {
    return this.http.get<TripAnomalyReport>('/api/trip-anomalies', { params: { window } });
  }
}
