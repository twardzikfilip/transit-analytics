import { createFeature, createReducer, on } from '@ngrx/store';
import { DelayBand, FleetSeries, RouteActivity, StopDwell } from '../../core/models/models';
import { HistoryQuery, PositionHistoryActions } from './position-history.actions';

export interface PositionHistoryState {
  query: HistoryQuery | null;
  /** Vehicles over time for the query. Kept while a new one loads, so the chart doesn't flash. */
  series: FleetSeries | null;
  /** Routes recorded in the window, with their mean delay: the route filter and the most and least delay. */
  routes: RouteActivity[];
  loadingRoutes: boolean;
  /** The stops with the longest mean dwell in the window; null until loaded. */
  dwell: StopDwell[] | null;
  loadingDwell: boolean;
  /** Vehicles and reports per delay band. */
  delays: DelayBand[];
  /** Operators on the feed, the city operator first; empty until loaded. */
  agencies: string[];
  loadingSeries: boolean;
  error: string | null;
}

const initialState: PositionHistoryState = {
  query: null,
  series: null,
  routes: [],
  loadingRoutes: false,
  dwell: null,
  loadingDwell: false,
  delays: [],
  agencies: [],
  loadingSeries: false,
  error: null,
};

export const positionHistoryFeature = createFeature({
  name: 'positionHistory',
  reducer: createReducer(
    initialState,
    on(PositionHistoryActions.load, (state, { query }) => ({
      ...state,
      query,
      loadingSeries: true,
      loadingRoutes: true,
      loadingDwell: true,
      error: null,
    })),
    on(PositionHistoryActions.fleetLoaded, (state, { series }) => ({
      ...state,
      series,
      loadingSeries: false,
    })),
    on(PositionHistoryActions.routesLoaded, (state, { routes }) => ({
      ...state,
      routes,
      loadingRoutes: false,
    })),
    on(PositionHistoryActions.dwellLoaded, (state, { dwell }) => ({
      ...state,
      dwell,
      loadingDwell: false,
    })),
    on(PositionHistoryActions.delaysLoaded, (state, { delays }) => ({ ...state, delays })),
    on(PositionHistoryActions.agenciesLoaded, (state, { agencies }) => ({ ...state, agencies })),
    on(PositionHistoryActions.failure, (state, { error }) => ({
      ...state,
      loadingSeries: false,
      loadingRoutes: false,
      loadingDwell: false,
      error,
    })),
  ),
});
