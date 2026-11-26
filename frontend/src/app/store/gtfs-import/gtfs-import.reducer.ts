import { createFeature, createReducer, on } from '@ngrx/store';
import { GtfsFeed, GtfsImportResult } from '../../core/models/models';
import { GtfsImportActions } from './gtfs-import.actions';

export interface GtfsImportState {
  /** The feeds that can be imported; null until loaded. */
  feeds: GtfsFeed[] | null;
  /** The feed being imported; null when none is. */
  running: string | null;
  /** Last successful import in this session. */
  result: GtfsImportResult | null;
  error: string | null;
}

const initialState: GtfsImportState = { feeds: null, running: null, result: null, error: null };

export const gtfsImportFeature = createFeature({
  name: 'gtfsImport',
  reducer: createReducer(
    initialState,
    on(GtfsImportActions.feedsLoaded, (state, { feeds }) => ({ ...state, feeds })),
    on(GtfsImportActions.feedsFailure, (state, { error }) => ({ ...state, feeds: [], error })),
    on(GtfsImportActions.importFeed, (state, { feed }) => ({
      ...state,
      running: feed,
      error: null,
    })),
    on(GtfsImportActions.importSuccess, (state, { result }) => ({
      ...state,
      running: null,
      result,
    })),
    on(GtfsImportActions.importFailure, (state, { error }) => ({
      ...state,
      running: null,
      error,
    })),
  ),
});
