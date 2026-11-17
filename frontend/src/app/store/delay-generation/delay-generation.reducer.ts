import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { DelayGenerationReport, DelayLineReport } from '../../core/models/models';
import { DelayGenerationActions } from './delay-generation.actions';

export interface DelayGenerationState {
  /** The network; kept while another window loads, so the page doesn't go blank. */
  report: DelayGenerationReport | null;
  loading: boolean;
  error: string | null;
  /** The chosen line, likewise. */
  line: DelayLineReport | null;
  lineLoading: boolean;
  lineError: string | null;
}

const initialState: DelayGenerationState = {
  report: null,
  loading: false,
  error: null,
  line: null,
  lineLoading: false,
  lineError: null,
};

const reducer: ActionReducer<DelayGenerationState> = createReducer(
  initialState,
  on(DelayGenerationActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(DelayGenerationActions.loaded, (state, { report }) => ({ ...state, report, loading: false })),
  on(DelayGenerationActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  on(DelayGenerationActions.loadLine, (state) => ({
    ...state,
    lineLoading: true,
    lineError: null,
  })),
  on(DelayGenerationActions.lineLoaded, (state, { report }) => ({
    ...state,
    line: report,
    lineLoading: false,
  })),
  on(DelayGenerationActions.lineFailure, (state, { error }) => ({
    ...state,
    lineLoading: false,
    lineError: error,
  })),
  on(DelayGenerationActions.clearLine, (state) => ({
    ...state,
    line: null,
    lineLoading: false,
    lineError: null,
  })),
);

export const delayGenerationFeature = createFeature({ name: 'delayGeneration', reducer });
