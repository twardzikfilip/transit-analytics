import { HttpClient, HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Filters, Page } from '../models/models';

/** A list the backend sends a page at a time, in its own order. */
export abstract class PagedApi<T> {
  protected readonly http = inject(HttpClient);

  protected constructor(protected readonly baseUrl: string) {}

  list(page = 0, size = 50, filters: Filters = {}): Observable<Page<T>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filters)) {
      if (value !== null && value !== undefined && value !== '') params = params.set(key, value);
    }
    return this.http.get<Page<T>>(this.baseUrl, { params });
  }
}
