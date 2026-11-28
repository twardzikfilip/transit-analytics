import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { RouteDetail, RoutePerformance, RouteRef } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';

export interface RouteDetailState {
  routes: RouteRef[];
  /** The selected route (route GTFS id). */
  route: string | null;
  /** Timetable detail of the selected route; the previous route's stays while the next one loads. */
  detail: RouteDetail | null;
  /** Measured performance of the selected route, or null while it loads. */
  performance: RoutePerformance | null;
  loadingDetail: boolean;
  loadingPerformance: boolean;
  error: string | null;
}

const initialState: RouteDetailState = {
  routes: [],
  route: null,
  detail: null,
  performance: null,
  loadingDetail: false,
  loadingPerformance: false,
  error: null,
};

const reducer: ActionReducer<RouteDetailState> = createReducer(
  initialState,
  on(RouteDetailActions.routesLoaded, (state, { routes }) => ({ ...state, routes })),
  on(RouteDetailActions.select, (state, { route }) => ({
    ...state,
    route,
    performance: null,
    loadingDetail: true,
    loadingPerformance: true,
    error: null,
  })),
  on(RouteDetailActions.detailLoaded, (state, { detail }) => ({
    ...state,
    detail,
    loadingDetail: false,
  })),
  on(RouteDetailActions.performanceLoaded, (state, { route, performance }) =>
    route === state.route ? { ...state, performance, loadingPerformance: false } : state,
  ),
  on(RouteDetailActions.failure, (state, { error }) => ({
    ...state,
    loadingDetail: false,
    loadingPerformance: false,
    error,
  })),
);

export const routeDetailFeature = createFeature({ name: 'routeDetail', reducer });
