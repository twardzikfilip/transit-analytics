import { HttpClient, HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../models/models';

/** Generic REST client matching the backend CrudController endpoints. */
export abstract class CrudApiService<T, P = Omit<T, 'id'>> {
  protected readonly http = inject(HttpClient);

  protected constructor(protected readonly baseUrl: string) {}

  list(page = 0, size = 50, sort = 'id'): Observable<Page<T>> {
    const params = new HttpParams().set('page', page).set('size', size).set('sort', sort);
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
