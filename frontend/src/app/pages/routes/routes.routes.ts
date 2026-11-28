import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as routeStatsEffects from '../../store/route-stats/route-stats.effects';
import { routeStatsFeature } from '../../store/route-stats/route-stats.reducer';
import * as routeDetailEffects from '../../store/route-detail/route-detail.effects';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { RoutesPage } from './routes';
import { RoutesOverview } from './routes-overview';
import { openFormerQueryLink } from '../../shared/url-query';

/**
 * Routes (every route, ranked) and one route's page, with their store slices (the routes, and every route's weekday
 * statistics) loaded on first visit.
 */
export const ROUTES_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(routeDetailFeature),
      provideEffects(routeDetailEffects),
      provideState(routeStatsFeature),
      provideEffects(routeStatsEffects),
    ],
    children: [
      {
        path: '',
        component: RoutesOverview,
        canActivate: [openFormerQueryLink('line', ['/routes/route'])],
      },
      {
        path: 'route/:route',
        title: (route) => `titles.route:${route.paramMap.get('route')}`,
        component: RoutesPage,
      },
    ],
  },
];
