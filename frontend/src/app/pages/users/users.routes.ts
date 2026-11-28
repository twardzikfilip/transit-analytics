import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as usersEffects from '../../store/users/users.effects';
import { usersFeature } from '../../store/users/users.reducer';
import { Users } from './users';

/** Users (admins only), with its store slice, loaded on first visit. */
export const USERS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(usersFeature), provideEffects(usersEffects)],
    component: Users,
  },
];
