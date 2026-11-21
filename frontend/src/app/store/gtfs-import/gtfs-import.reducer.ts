import { createFeature, createReducer, on } from '@ngrx/store';
import { GtfsImportResult } from '../../core/models/models';
import { GtfsImportActions } from './gtfs-import.actions';

export interface GtfsImportState {
  running: boolean;
  /** Last successful import in this session. */
  result: GtfsImportResult | null;
  error: string | null;
}

const initialState: GtfsImportState = { running: false, result: null, error: null };

export const gtfsImportFeature = createFeature({
  name: 'gtfsImport',
  reducer: createReducer(
    initialState,
    on(GtfsImportActions.importBialystok, (state) => ({ ...state, running: true, error: null })),
    on(GtfsImportActions.importSuccess, (state, { result }) => ({
      ...state,
      running: false,
      result,
    })),
    on(GtfsImportActions.importFailure, (state, { error }) => ({
      ...state,
      running: false,
      error,
    })),
  ),
});
