import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Versions } from '../models/models';

@Injectable({ providedIn: 'root' })
export class VersionApi {
  private readonly http = inject(HttpClient);

  versions() {
    return this.http.get<Versions>('/api/version');
  }
}
