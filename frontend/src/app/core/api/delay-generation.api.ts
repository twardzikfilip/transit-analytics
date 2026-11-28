import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DelayGenerationReport, DelayRouteReport, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** Delay generation: where buses gain or recover delay between stops. */
@Injectable({ providedIn: 'root' })
export class DelayGenerationApi {
  private readonly http = inject(HttpClient);

  /** Every stretch of the network, and each route's gain per run. */
  report(window: WindowQuery): Observable<DelayGenerationReport> {
    return this.http.get<DelayGenerationReport>('/api/delay-generation', {
      params: windowParams(window),
    });
  }

  /** One route: per direction, its stretches by hour, its brigades and example runs. */
  route(window: WindowQuery, route: string): Observable<DelayRouteReport> {
    return this.http.get<DelayRouteReport>(
      `/api/delay-generation/routes/${encodeURIComponent(route)}`,
      { params: windowParams(window) },
    );
  }
}
