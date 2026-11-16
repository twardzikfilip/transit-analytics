import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { LineDetail, LinePerformance, LineRef } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';

export interface RouteDetailState {
  lines: LineRef[];
  /** The selected line (route GTFS id). */
  line: string | null;
  /** Timetable detail of the selected line; the previous line's stays while the next one loads. */
  detail: LineDetail | null;
  /** Measured performance of the selected line, or null while it loads. */
  performance: LinePerformance | null;
  loadingDetail: boolean;
  loadingPerformance: boolean;
  error: string | null;
}

const initialState: RouteDetailState = {
  lines: [],
  line: null,
  detail: null,
  performance: null,
  loadingDetail: false,
  loadingPerformance: false,
  error: null,
};

const reducer: ActionReducer<RouteDetailState> = createReducer(
  initialState,
  on(RouteDetailActions.linesLoaded, (state, { lines }) => ({ ...state, lines })),
  on(RouteDetailActions.select, (state, { line }) => ({
    ...state,
    line,
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
  on(RouteDetailActions.performanceLoaded, (state, { line, performance }) =>
    line === state.line ? { ...state, performance, loadingPerformance: false } : state,
  ),
  on(RouteDetailActions.failure, (state, { error }) => ({
    ...state,
    loadingDetail: false,
    loadingPerformance: false,
    error,
  })),
);

export const routeDetailFeature = createFeature({ name: 'routeDetail', reducer });
