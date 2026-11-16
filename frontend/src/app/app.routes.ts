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
      { path: 'positions', loadChildren: () => import('./pages/positions/positions.routes').then((m) => m.POSITIONS_ROUTES) },
      { path: '', loadChildren: () => import('./pages/timetable.routes').then((m) => m.TIMETABLE_ROUTES) },
      { path: 'settings', loadChildren: () => import('./pages/settings/settings.routes').then((m) => m.SETTINGS_ROUTES) },
      // Admin-only pages go here, e.g.:
      // { path: 'users', canActivate: [roleGuard], data: { roles: ['ADMIN'] }, loadComponent: ... },
    ],
  },
  { path: '**', redirectTo: '' },
];
