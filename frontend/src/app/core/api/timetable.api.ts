import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { GtfsFeed, GtfsImportResult, Stop, StopTime, TimetableCoverage } from '../models/models';
import { PagedApi } from './paged-api';

@Injectable({ providedIn: 'root' })
export class StopApi extends PagedApi<Stop> {
  constructor() {
    super('/api/stops');
  }
}

@Injectable({ providedIn: 'root' })
export class StopTimeApi extends PagedApi<StopTime> {
  constructor() {
    super('/api/stop-times');
  }
}

/** Imports public transport timetables (GTFS). */
@Injectable({ providedIn: 'root' })
export class GtfsApi {
  private readonly http = inject(HttpClient);

  /** Until when each timetable runs. */
  coverage(): Observable<TimetableCoverage> {
    return this.http.get<TimetableCoverage>('/api/gtfs/coverage');
  }

  /** Every feed that can be imported, with its last import. */
  feeds(): Observable<GtfsFeed[]> {
    return this.http.get<GtfsFeed[]>('/api/gtfs/feeds');
  }

  /** Imports a feed; a download the same as its last import is not imported again (unchanged). */
  importFeed(feed: string): Observable<GtfsImportResult> {
    return this.http.post<GtfsImportResult>(`/api/gtfs/${encodeURIComponent(feed)}/import`, null);
  }
}
