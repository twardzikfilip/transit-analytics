import { createEntityAdapter } from '@ngrx/entity';
import { ActionReducer, createFeature } from '@ngrx/store';
import { Trip } from '../../core/models/models';
import { PagedState, pagedReducer } from '../paged';
import { TripsActions } from './trips.actions';

/** One server-side page of trips, plus the query it answers. */
export type TripsState = PagedState<Trip>;

const adapter = createEntityAdapter<Trip>();
const reducer: ActionReducer<TripsState> = pagedReducer(TripsActions, adapter);

export const tripsFeature = createFeature({
  name: 'trips',
  reducer,
  extraSelectors: ({ selectTripsState }) => adapter.getSelectors(selectTripsState),
});
