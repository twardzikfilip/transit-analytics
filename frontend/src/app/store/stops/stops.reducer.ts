import { createEntityAdapter } from '@ngrx/entity';
import { ActionReducer, createFeature } from '@ngrx/store';
import { Stop } from '../../core/models/models';
import { PagedState, pagedReducer } from '../paged';
import { StopsActions } from './stops.actions';

/** One server-side page of stops, plus the query it answers. */
export type StopsState = PagedState<Stop>;

const adapter = createEntityAdapter<Stop>();
const reducer: ActionReducer<StopsState> = pagedReducer(StopsActions, adapter);

export const stopsFeature = createFeature({
  name: 'stops',
  reducer,
  extraSelectors: ({ selectStopsState }) => adapter.getSelectors(selectStopsState),
});
