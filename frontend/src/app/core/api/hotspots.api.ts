import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { HotspotReport, StatsWindow } from '../models/models';

/** Stop hotspots: where buses stand still away from stops. */
@Injectable({ providedIn: 'root' })
export class HotspotApi {
  private readonly http = inject(HttpClient);

  /** The report for a window, for one line or (null) all of them. */
  report(window: StatsWindow, line: string | null = null): Observable<HotspotReport> {
    return this.http.get<HotspotReport>('/api/hotspots', {
      params: line ? { window, line } : { window },
    });
  }
}
