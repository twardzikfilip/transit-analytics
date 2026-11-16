import { HttpClient, HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Filters, Page } from '../models/models';

/** Generic REST client matching the backend CrudController endpoints. */
export abstract class CrudApiService<T, P = Omit<T, 'id'>> {
  protected readonly http = inject(HttpClient);

  protected constructor(protected readonly baseUrl: string) {}

  /** @param sort one or more "property[,asc|desc]" */
  list(page = 0, size = 50, sort: string | string[] = 'id', filters: Filters = {}): Observable<Page<T>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const s of Array.isArray(sort) ? sort : [sort]) params = params.append('sort', s);
    for (const [key, value] of Object.entries(filters)) {
      if (value !== null && value !== undefined && value !== '') params = params.set(key, value);
    }
    return this.http.get<Page<T>>(this.baseUrl, { params });
  }

  get(id: number): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}/${id}`);
  }

  create(payload: P): Observable<T> {
    return this.http.post<T>(this.baseUrl, payload);
  }

  update(id: number, payload: P): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}/${id}`, payload);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
