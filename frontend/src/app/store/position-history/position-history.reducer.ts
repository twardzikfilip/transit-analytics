import { createFeature, createReducer, on } from '@ngrx/store';
import {
  DelayBand,
  FleetSeries,
  Page,
  PositionSample,
  RouteActivity,
} from '../../core/models/models';
import { HistoryQuery, PositionHistoryActions } from './position-history.actions';

export interface PositionHistoryState {
  query: HistoryQuery | null;
  /** Vehicles over time for the query. Kept while a new one loads, so the chart doesn't flash. */
  series: FleetSeries | null;
  /** Lines recorded in the window, for the line filter. */
  routes: RouteActivity[];
  /** Vehicles and reports per minute of delay. */
  delays: DelayBand[];
  /** One page of raw position reports, newest first. */
  samples: Page<PositionSample> | null;
  loadingSeries: boolean;
  loadingSamples: boolean;
  error: string | null;
}

const initialState: PositionHistoryState = {
  query: null,
  series: null,
  routes: [],
  delays: [],
  samples: null,
  loadingSeries: false,
  loadingSamples: false,
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
    on(PositionHistoryActions.loadSamples, (state) => ({ ...state, loadingSamples: true })),
    on(PositionHistoryActions.samplesLoaded, (state, { samples }) => ({
      ...state,
      samples,
      loadingSamples: false,
    })),
    on(PositionHistoryActions.failure, (state, { error }) => ({
      ...state,
      loadingSeries: false,
      loadingSamples: false,
      error,
    })),
  ),
});
