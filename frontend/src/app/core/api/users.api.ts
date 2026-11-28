import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AppUser, NewUser } from '../models/models';

/** The app's users (admins only). */
@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly http = inject(HttpClient);

  list(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>('/api/users');
  }

  create(user: NewUser): Observable<AppUser> {
    return this.http.post<AppUser>('/api/users', user);
  }

  delete(username: string): Observable<void> {
    return this.http.delete<void>(`/api/users/${encodeURIComponent(username)}`);
  }
}
