import { Injectable } from '@angular/core';
import { User, UserPayload, UserRole } from '../models/models';
import { CrudApiService } from './crud-api.service';

@Injectable({ providedIn: 'root' })
export class UserApi extends CrudApiService<User, UserPayload> {
  constructor() {
    super('/api/users');
  }
}

@Injectable({ providedIn: 'root' })
export class UserRoleApi extends CrudApiService<UserRole> {
  constructor() {
    super('/api/roles');
  }
}
