import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CityRailProposal,
  CityRailReport,
  ProposalVariant,
  RailBusComparison,
} from '../models/models';

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

  /** One solution's routes and timetables at a frequency. */
  variant(solution: string, frequency: string): Observable<ProposalVariant> {
    return this.http.get<ProposalVariant>(
      `/api/city-rail/proposal/${encodeURIComponent(solution)}/${encodeURIComponent(frequency)}`,
    );
  }
}
