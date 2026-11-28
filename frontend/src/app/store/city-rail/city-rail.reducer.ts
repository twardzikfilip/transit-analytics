import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import {
  CityRailProposal,
  CityRailReport,
  ProposalVariant,
  RailBusComparison,
} from '../../core/models/models';
import { CityRailActions } from './city-rail.actions';

export interface CityRailState {
  report: CityRailReport | null;
  loading: boolean;
  error: string | null;
  /** Train against bus, loaded on its own: it takes longer. */
  comparison: RailBusComparison | null;
  comparisonLoading: boolean;
  /** City Rail proposal no. 1, loaded by its pages. */
  proposal: CityRailProposal | null;
  proposalLoading: boolean;
  /** The routes of the solution and frequency shown, and which they are. */
  variant: { solution: string; variant: ProposalVariant } | null;
  variantLoading: boolean;
}

const initialState: CityRailState = {
  report: null,
  loading: false,
  error: null,
  comparison: null,
  comparisonLoading: false,
  proposal: null,
  proposalLoading: false,
  variant: null,
  variantLoading: false,
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
  on(CityRailActions.loadProposal, (state) => ({ ...state, proposalLoading: true })),
  on(CityRailActions.proposalLoaded, (state, { proposal }) => ({
    ...state,
    proposal,
    proposalLoading: false,
  })),
  on(CityRailActions.proposalFailure, (state, { error }) => ({
    ...state,
    proposalLoading: false,
    error,
  })),
  on(CityRailActions.loadVariant, (state) => ({ ...state, variantLoading: true })),
  on(CityRailActions.variantLoaded, (state, { solution, variant }) => ({
    ...state,
    variant: { solution, variant },
    variantLoading: false,
  })),
  on(CityRailActions.variantFailure, (state, { error }) => ({
    ...state,
    variantLoading: false,
    error,
  })),
);

export const cityRailFeature = createFeature({ name: 'cityRail', reducer });
