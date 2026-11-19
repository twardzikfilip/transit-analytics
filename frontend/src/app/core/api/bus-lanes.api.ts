import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { BusLaneReport, BusLanes, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** Bus lanes: where they are (fetched once per session), and speeds and delays on them. */
@Injectable({ providedIn: 'root' })
export class BusLaneApi {
  private readonly http = inject(HttpClient);
  private readonly cached = this.http.get<BusLanes>('/api/bus-lanes').pipe(shareReplay(1));

  lanes(): Observable<BusLanes> {
    return this.cached;
  }

  report(window: WindowQuery): Observable<BusLaneReport> {
    return this.http.get<BusLaneReport>('/api/bus-lanes/report', { params: windowParams(window) });
  }
}
