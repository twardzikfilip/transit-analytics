import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { CityRailReport, RailBusComparison } from '../../core/models/models';
import { CityRailActions } from './city-rail.actions';

export interface CityRailState {
  report: CityRailReport | null;
  loading: boolean;
  error: string | null;
  /** Train against bus, loaded on its own: it takes longer. */
  comparison: RailBusComparison | null;
  comparisonLoading: boolean;
}

const initialState: CityRailState = {
  report: null,
  loading: false,
  error: null,
  comparison: null,
  comparisonLoading: false,
};

const reducer: ActionReducer<CityRailState> = createReducer(
  initialState,
  on(CityRailActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(CityRailActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(CityRailActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  on(CityRailActions.loadComparison, (state) => ({ ...state, comparisonLoading: true })),
  on(CityRailActions.comparisonLoaded, (state, { comparison }) => ({
    ...state,
    comparison,
    comparisonLoading: false,
  })),
  on(CityRailActions.comparisonFailure, (state, { error }) => ({
    ...state,
    comparisonLoading: false,
    error,
  })),
);

export const cityRailFeature = createFeature({ name: 'cityRail', reducer });
