import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { NetworkCoverage } from '../models/models';

/** Stop coverage of the city and its districts' densities; changes only with the timetable, fetched once a session. */
@Injectable({ providedIn: 'root' })
export class NetworkCoverageApi {
  private readonly http = inject(HttpClient);
  private readonly cached = this.http
    .get<NetworkCoverage>('/api/network-coverage')
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  coverage(): Observable<NetworkCoverage> {
    return this.cached;
  }
}
