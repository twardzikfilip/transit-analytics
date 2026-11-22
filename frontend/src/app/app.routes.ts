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
      {
        path: 'home',
        title: 'titles.home',
        loadChildren: () => import('./pages/home/home.routes').then((m) => m.HOME_ROUTES),
      },
      {
        path: 'vehicles',
        loadChildren: () => import('./pages/fleet/fleet.routes').then((m) => m.FLEET_ROUTES),
      },
      // Former pages, now part of Fleet, Routes and Home.
      { path: 'fleet', redirectTo: 'vehicles' },
      { path: 'vehicle-stats', redirectTo: 'vehicles' },
      { path: 'route-detail', redirectTo: 'routes' },
      { path: 'live-map', redirectTo: 'home' },
      { path: 'positions', redirectTo: 'home' },
      { path: 'delays', redirectTo: 'home' },
      // Line statistics: each line's regularity is now on Routes.
      { path: 'lines', redirectTo: 'routes' },
      {
        path: 'hotspots',
        title: 'titles.hotspots',
        loadChildren: () =>
          import('./pages/hotspots/hotspots.routes').then((m) => m.HOTSPOTS_ROUTES),
      },
      {
        path: 'brigades',
        title: 'titles.brigades',
        loadChildren: () =>
          import('./pages/brigades/brigades.routes').then((m) => m.BRIGADES_ROUTES),
      },
      {
        path: 'trip-anomalies',
        title: 'titles.tripAnomalies',
        loadChildren: () =>
          import('./pages/trip-anomalies/trip-anomalies.routes').then(
            (m) => m.TRIP_ANOMALIES_ROUTES,
          ),
      },
      {
        path: 'delay-generation',
        title: 'titles.delayGeneration',
        loadChildren: () =>
          import('./pages/delay-generation/delay-generation.routes').then(
            (m) => m.DELAY_GENERATION_ROUTES,
          ),
      },
      {
        path: 'corridors',
        title: 'titles.corridors',
        loadChildren: () =>
          import('./pages/corridors/corridors.routes').then((m) => m.CORRIDORS_ROUTES),
      },
      {
        path: 'bus-lanes',
        title: 'titles.busLanes',
        loadChildren: () =>
          import('./pages/bus-lanes/bus-lanes.routes').then((m) => m.BUS_LANES_ROUTES),
      },
      {
        path: 'network-coverage',
        title: 'titles.networkCoverage',
        loadChildren: () =>
          import('./pages/network-coverage/network-coverage.routes').then(
            (m) => m.NETWORK_COVERAGE_ROUTES,
          ),
      },
      {
        path: 'routes',
        title: 'titles.routes',
        loadChildren: () => import('./pages/routes/routes.routes').then((m) => m.ROUTES_ROUTES),
      },
      {
        path: 'sources',
        title: 'titles.sources',
        loadComponent: () => import('./pages/sources/sources').then((m) => m.Sources),
      },
      {
        path: '',
        loadChildren: () => import('./pages/timetable.routes').then((m) => m.TIMETABLE_ROUTES),
      },
      {
        path: 'settings',
        title: 'titles.settings',
        loadChildren: () =>
          import('./pages/settings/settings.routes').then((m) => m.SETTINGS_ROUTES),
      },
      // Admin-only pages go here, e.g.:
      // { path: 'users', canActivate: [roleGuard], data: { roles: ['ADMIN'] }, loadComponent: ... },
    ],
  },
  { path: '**', redirectTo: '' },
];
