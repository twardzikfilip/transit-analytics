import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { GtfsImportResult, Route, Stop, StopTime, Trip } from '../models/models';
import { CrudApiService } from './crud-api.service';

@Injectable({ providedIn: 'root' })
export class RouteApi extends CrudApiService<Route, unknown> {
  constructor() {
    super('/api/routes');
  }
}

@Injectable({ providedIn: 'root' })
export class StopApi extends CrudApiService<Stop, unknown> {
  constructor() {
    super('/api/stops');
  }
}

@Injectable({ providedIn: 'root' })
export class TripApi extends CrudApiService<Trip, unknown> {
  constructor() {
    super('/api/trips');
  }
}

@Injectable({ providedIn: 'root' })
export class StopTimeApi extends CrudApiService<StopTime, unknown> {
  constructor() {
    super('/api/stop-times');
  }
}

/** Imports public transport timetables (GTFS). */
@Injectable({ providedIn: 'root' })
export class GtfsApi {
  private readonly http = inject(HttpClient);

  importBialystok(): Observable<GtfsImportResult> {
    return this.http.post<GtfsImportResult>('/api/gtfs/bialystok/import', null);
  }
}
