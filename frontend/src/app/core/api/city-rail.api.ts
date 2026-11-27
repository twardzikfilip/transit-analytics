import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CityRailReport, RailBusComparison } from '../models/models';

@Injectable({ providedIn: 'root' })
export class CityRailApi {
  private readonly http = inject(HttpClient);

  report(): Observable<CityRailReport> {
    return this.http.get<CityRailReport>('/api/city-rail');
  }

  /** Train against bus between the stations in the city. */
  comparison(): Observable<RailBusComparison> {
    return this.http.get<RailBusComparison>('/api/city-rail/comparison');
  }
}
