import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DelayGenerationReport, DelayLineReport, WindowQuery } from '../models/models';
import { windowParams } from './window-params';

/** Delay generation: where buses gain or recover delay between stops. */
@Injectable({ providedIn: 'root' })
export class DelayGenerationApi {
  private readonly http = inject(HttpClient);

  /** Every stretch of the network, and each line's gain per run. */
  report(window: WindowQuery): Observable<DelayGenerationReport> {
    return this.http.get<DelayGenerationReport>('/api/delay-generation', {
      params: windowParams(window),
    });
  }

  /** One line: per direction, its stretches by hour, its brigades and example runs. */
  line(window: WindowQuery, line: string): Observable<DelayLineReport> {
    return this.http.get<DelayLineReport>(
      `/api/delay-generation/lines/${encodeURIComponent(line)}`,
      { params: windowParams(window) },
    );
  }
}
