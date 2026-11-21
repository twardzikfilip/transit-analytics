import { createFeature, createReducer, on } from '@ngrx/store';
import { DelayBand, FleetSeries, RouteActivity } from '../../core/models/models';
import { HistoryQuery, PositionHistoryActions } from './position-history.actions';

export interface PositionHistoryState {
  query: HistoryQuery | null;
  /** Vehicles over time for the query. Kept while a new one loads, so the chart doesn't flash. */
  series: FleetSeries | null;
  /** Lines recorded in the window, for the line filter. */
  routes: RouteActivity[];
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
      error: null,
    })),
    on(PositionHistoryActions.fleetLoaded, (state, { series }) => ({
      ...state,
      series,
      loadingSeries: false,
    })),
    on(PositionHistoryActions.routesLoaded, (state, { routes }) => ({ ...state, routes })),
    on(PositionHistoryActions.delaysLoaded, (state, { delays }) => ({ ...state, delays })),
    on(PositionHistoryActions.agenciesLoaded, (state, { agencies }) => ({ ...state, agencies })),
    on(PositionHistoryActions.failure, (state, { error }) => ({
      ...state,
      loadingSeries: false,
      error,
    })),
  ),
});
