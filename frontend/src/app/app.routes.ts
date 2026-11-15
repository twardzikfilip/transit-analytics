import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guards';
import { Shell } from './layout/shell/shell';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login/login').then((m) => m.Login) },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      { path: 'home', loadComponent: () => import('./pages/home/home').then((m) => m.Home) },
      { path: 'vehicles', loadComponent: () => import('./pages/vehicles/vehicles').then((m) => m.Vehicles) },
      { path: 'live-map', loadComponent: () => import('./pages/live-map/live-map').then((m) => m.LiveMap) },
      { path: 'settings', loadComponent: () => import('./pages/settings/settings').then((m) => m.Settings) },
      // Admin-only pages go here, e.g.:
      // { path: 'users', canActivate: [roleGuard], data: { roles: ['ADMIN'] }, loadComponent: ... },
    ],
  },
  { path: '**', redirectTo: '' },
];
