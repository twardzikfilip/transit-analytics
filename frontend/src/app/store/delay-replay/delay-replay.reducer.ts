import { createFeature, createReducer, on } from '@ngrx/store';
import { ReplayResponse, RouteActivity } from '../../core/models/models';
import { DelayReplayActions, ReplayQuery } from './delay-replay.actions';

export interface DelayReplayState {
  query: ReplayQuery | null;
  /** Frames for the query; kept while a new one loads. */
  replay: ReplayResponse | null;
  /** Lines recorded in the window, for the line filter. */
  routes: RouteActivity[];
  loading: boolean;
  error: string | null;
}

const initialState: DelayReplayState = {
  query: null,
  replay: null,
  routes: [],
  loading: false,
  error: null,
};

export const delayReplayFeature = createFeature({
  name: 'delayReplay',
  reducer: createReducer(
    initialState,
    on(DelayReplayActions.load, (state, { query }) => ({
      ...state,
      query,
      loading: true,
      error: null,
    })),
    on(DelayReplayActions.replayLoaded, (state, { replay }) => ({
      ...state,
      replay,
      loading: false,
    })),
    on(DelayReplayActions.routesLoaded, (state, { routes }) => ({ ...state, routes })),
    on(DelayReplayActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
});
