import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { DelayGenerationReport, DelayRouteReport } from '../../core/models/models';
import { DelayGenerationActions } from './delay-generation.actions';

export interface DelayGenerationState {
  /** The network; kept while another window loads, so the page doesn't go blank. */
  report: DelayGenerationReport | null;
  loading: boolean;
  error: string | null;
  /** The chosen route, likewise. */
  route: DelayRouteReport | null;
  routeLoading: boolean;
  routeError: string | null;
}

const initialState: DelayGenerationState = {
  report: null,
  loading: false,
  error: null,
  route: null,
  routeLoading: false,
  routeError: null,
};

const reducer: ActionReducer<DelayGenerationState> = createReducer(
  initialState,
  on(DelayGenerationActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(DelayGenerationActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(DelayGenerationActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  on(DelayGenerationActions.loadRoute, (state) => ({
    ...state,
    routeLoading: true,
    routeError: null,
  })),
  on(DelayGenerationActions.routeLoaded, (state, { report }) => ({
    ...state,
    route: report,
    routeLoading: false,
  })),
  on(DelayGenerationActions.routeFailure, (state, { error }) => ({
    ...state,
    routeLoading: false,
    routeError: error,
  })),
  on(DelayGenerationActions.clearRoute, (state) => ({
    ...state,
    route: null,
    routeLoading: false,
    routeError: null,
  })),
);

export const delayGenerationFeature = createFeature({ name: 'delayGeneration', reducer });
