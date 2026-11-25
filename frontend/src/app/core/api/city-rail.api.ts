import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CityRailReport } from '../models/models';

@Injectable({ providedIn: 'root' })
export class CityRailApi {
  private readonly http = inject(HttpClient);

  report(): Observable<CityRailReport> {
    return this.http.get<CityRailReport>('/api/city-rail');
  }
}
