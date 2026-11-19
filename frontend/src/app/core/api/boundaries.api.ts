import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { CityBoundaries } from '../models/models';

/** City limits and centre for maps; fixed data, fetched once per session. */
@Injectable({ providedIn: 'root' })
export class BoundaryApi {
  private readonly http = inject(HttpClient);
  private readonly cached = this.http.get<CityBoundaries>('/api/boundaries').pipe(shareReplay(1));

  boundaries(): Observable<CityBoundaries> {
    return this.cached;
  }
}
