import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { HotspotReport, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** Stop hotspots: where buses stand still away from stops. */
@Injectable({ providedIn: 'root' })
export class HotspotApi {
  private readonly http = inject(HttpClient);

  /** The report for a window, for one line or (null) all of them. */
  report(window: WindowQuery, line: string | null = null): Observable<HotspotReport> {
    const params = windowParams(window);
    return this.http.get<HotspotReport>('/api/hotspots', {
      params: line ? { ...params, line } : params,
    });
  }
}
