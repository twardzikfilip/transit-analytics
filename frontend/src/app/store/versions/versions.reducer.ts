import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { Versions } from '../../core/models/models';
import { VersionsActions } from './versions.actions';

export interface VersionsState {
  versions: Versions | null;
  loading: boolean;
  error: string | null;
}

const initialState: VersionsState = { versions: null, loading: false, error: null };

const reducer: ActionReducer<VersionsState> = createReducer(
  initialState,
  on(VersionsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(VersionsActions.loaded, (state, { versions }) => ({ ...state, versions, loading: false })),
  on(VersionsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const versionsFeature = createFeature({ name: 'versions', reducer });
