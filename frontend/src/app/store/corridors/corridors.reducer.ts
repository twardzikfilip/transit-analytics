import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { CorridorReport } from '../../core/models/models';
import { CorridorsActions } from './corridors.actions';

export interface CorridorsState {
  report: CorridorReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: CorridorsState = { report: null, loading: false, error: null };

const reducer: ActionReducer<CorridorsState> = createReducer(
  initialState,
  on(CorridorsActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(CorridorsActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(CorridorsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const corridorsFeature = createFeature({ name: 'corridors', reducer });
