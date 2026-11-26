import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { CityRailReport } from '../../core/models/models';
import { CityRailActions } from './city-rail.actions';

export interface CityRailState {
  report: CityRailReport | null;
  loading: boolean;
  error: string | null;
}

const initialState: CityRailState = { report: null, loading: false, error: null };

const reducer: ActionReducer<CityRailState> = createReducer(
  initialState,
  on(CityRailActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(CityRailActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(CityRailActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const cityRailFeature = createFeature({ name: 'cityRail', reducer });
