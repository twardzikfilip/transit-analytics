import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CityRailProposal, CityRailReport, RailBusComparison } from '../models/models';

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

  /** City Rail proposal no. 1, timetabled around today's trains. */
  proposal(): Observable<CityRailProposal> {
    return this.http.get<CityRailProposal>('/api/city-rail/proposal');
  }
}
