import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { RoleName } from '../models/models';
import { authFeature } from '../../store/auth/auth.reducer';

/** Allows navigation only for logged-in users. */
export const authGuard: CanActivateFn = () => {
  const loggedIn = inject(Store).selectSignal(authFeature.selectIsAuthenticated)();
  return loggedIn || inject(Router).createUrlTree(['/login']);
};

/** Allows navigation only if the user's role is listed in route `data.roles`. */
export const roleGuard: CanActivateFn = (route) => {
  const roles = (route.data['roles'] ?? []) as RoleName[];
  const role = inject(Store).selectSignal(authFeature.selectRole)();
  return (role !== null && roles.includes(role)) || inject(Router).createUrlTree(['/home']);
};
