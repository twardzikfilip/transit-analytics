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
      { path: 'vehicles', loadChildren: () => import('./pages/vehicles/vehicles.routes').then((m) => m.VEHICLES_ROUTES) },
      {
        path: 'vehicle-stats',
        loadChildren: () => import('./pages/vehicle-stats/vehicle-stats.routes').then((m) => m.VEHICLE_STATS_ROUTES),
      },
      {
        path: 'hotspots',
        loadChildren: () => import('./pages/hotspots/hotspots.routes').then((m) => m.HOTSPOTS_ROUTES),
      },
      { path: 'live-map', loadChildren: () => import('./pages/live-map/live-map.routes').then((m) => m.LIVE_MAP_ROUTES) },
      { path: 'lines', loadChildren: () => import('./pages/line-stats/line-stats.routes').then((m) => m.LINE_STATS_ROUTES) },
      {
        path: 'corridors',
        loadChildren: () => import('./pages/corridors/corridors.routes').then((m) => m.CORRIDORS_ROUTES),
      },
      { path: 'delays', loadChildren: () => import('./pages/delays/delays.routes').then((m) => m.DELAYS_ROUTES) },
      {
        path: 'route-detail',
        loadChildren: () => import('./pages/route-detail/route-detail.routes').then((m) => m.ROUTE_DETAIL_ROUTES),
      },
      { path: 'positions', loadChildren: () => import('./pages/positions/positions.routes').then((m) => m.POSITIONS_ROUTES) },
      { path: '', loadChildren: () => import('./pages/timetable.routes').then((m) => m.TIMETABLE_ROUTES) },
      { path: 'settings', loadChildren: () => import('./pages/settings/settings.routes').then((m) => m.SETTINGS_ROUTES) },
      // Admin-only pages go here, e.g.:
      // { path: 'users', canActivate: [roleGuard], data: { roles: ['ADMIN'] }, loadComponent: ... },
    ],
  },
  { path: '**', redirectTo: '' },
];
