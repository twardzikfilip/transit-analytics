import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BrigadeDay, BrigadeList, BrigadeSchedule } from '../models/models';

/**
 * Brigades (vehicle duties): one's runs on a day from the timetable, the ones running on a day, and one brigade's day
 * scheduled against what ran.
 */
@Injectable({ providedIn: 'root' })
export class BrigadeApi {
  private readonly http = inject(HttpClient);

  /** The brigade's runs on the service day that `at` falls in (default: today's). */
  schedule(brigade: string, at?: Date): Observable<BrigadeSchedule> {
    return this.http.get<BrigadeSchedule>(`/api/brigades/${encodeURIComponent(brigade)}/schedule`, {
      params: at ? { at: at.toISOString() } : {},
    });
  }

  /** Every brigade of an agency (default: the city's, BKM), whatever day it runs. */
  list(agency: string | null): Observable<BrigadeList> {
    const params: Record<string, string> = {};
    if (agency) params['agency'] = agency;
    return this.http.get<BrigadeList>('/api/brigades', { params });
  }

  day(brigade: string, date: string | null): Observable<BrigadeDay> {
    return this.http.get<BrigadeDay>(`/api/brigades/${encodeURIComponent(brigade)}/day`, {
      params: date ? { date } : {},
    });
  }
}
