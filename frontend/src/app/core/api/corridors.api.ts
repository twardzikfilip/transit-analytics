import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CorridorReport } from '../models/models';

@Injectable({ providedIn: 'root' })
export class CorridorApi {
  private readonly http = inject(HttpClient);

  report(): Observable<CorridorReport> {
    return this.http.get<CorridorReport>('/api/corridors');
  }
}
