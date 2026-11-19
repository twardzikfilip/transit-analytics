import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { BrigadeDay, BrigadeDayList } from '../../core/models/models';
import { BrigadesActions } from './brigades.actions';

export interface BrigadesState {
  list: BrigadeDayList | null;
  loadingList: boolean;
  /** The chosen brigade's day; kept while another loads, so the page doesn't go blank. */
  day: BrigadeDay | null;
  loadingDay: boolean;
  error: string | null;
}

const initialState: BrigadesState = {
  list: null,
  loadingList: false,
  day: null,
  loadingDay: false,
  error: null,
};

const reducer: ActionReducer<BrigadesState> = createReducer(
  initialState,
  on(BrigadesActions.loadList, (state) => ({ ...state, loadingList: true, error: null })),
  on(BrigadesActions.listLoaded, (state, { list }) => ({ ...state, list, loadingList: false })),
  on(BrigadesActions.loadDay, (state) => ({ ...state, loadingDay: true, error: null })),
  on(BrigadesActions.dayLoaded, (state, { day }) => ({ ...state, day, loadingDay: false })),
  on(BrigadesActions.clearDay, (state) => ({ ...state, day: null, loadingDay: false })),
  on(BrigadesActions.failure, (state, { error }) => ({
    ...state,
    loadingList: false,
    loadingDay: false,
    error,
  })),
);

export const brigadesFeature = createFeature({ name: 'brigades', reducer });
