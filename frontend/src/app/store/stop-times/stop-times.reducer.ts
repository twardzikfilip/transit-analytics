import { createEntityAdapter } from '@ngrx/entity';
import { ActionReducer, createFeature } from '@ngrx/store';
import { StopTime } from '../../core/models/models';
import { PagedState, pagedReducer } from '../paged';
import { StopTimesActions } from './stop-times.actions';

/** One server-side page of stop times, plus the query it answers. */
export type StopTimesState = PagedState<StopTime>;

const adapter = createEntityAdapter<StopTime>();
const reducer: ActionReducer<StopTimesState> = pagedReducer(StopTimesActions, adapter);

export const stopTimesFeature = createFeature({
  name: 'stopTimes',
  reducer,
  extraSelectors: ({ selectStopTimesState }) => adapter.getSelectors(selectStopTimesState),
});
