import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as routeDetailEffects from '../../store/route-detail/route-detail.effects';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { RouteDetail } from './route-detail';

/** Routes in detail, with its store slice, loaded on first visit. */
export const ROUTE_DETAIL_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(routeDetailFeature), provideEffects(routeDetailEffects)],
    component: RouteDetail,
  },
];
