import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as routeDetailEffects from '../../store/route-detail/route-detail.effects';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { RoutesPage } from './routes';

/** Routes, with its store slice, loaded on first visit. */
export const ROUTES_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(routeDetailFeature), provideEffects(routeDetailEffects)],
    component: RoutesPage,
  },
];
