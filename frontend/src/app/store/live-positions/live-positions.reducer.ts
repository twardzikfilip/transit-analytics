import { createEntityAdapter, EntityState } from '@ngrx/entity';
import { createFeature, createReducer, on } from '@ngrx/store';
import { LivePosition } from '../../core/models/models';
import { LivePositionsActions } from './live-positions.actions';

export interface LivePositionsState extends EntityState<LivePosition> {
  loading: boolean;
  error: string | null;
  /** ISO time of the last successful load. */
  lastUpdated: string | null;
}

const adapter = createEntityAdapter<LivePosition>({ selectId: (p) => p.vehicleNumber });

const initialState: LivePositionsState = adapter.getInitialState({
  loading: false,
  error: null,
  lastUpdated: null,
});

export const livePositionsFeature = createFeature({
  name: 'livePositions',
  reducer: createReducer(
    initialState,
    on(LivePositionsActions.load, (state) => ({ ...state, loading: true })),
    // Replace everything: vehicles that left the feed disappear from the map.
    on(LivePositionsActions.loadSuccess, (state, { positions, loadedAt }) =>
      adapter.setAll(positions, { ...state, loading: false, error: null, lastUpdated: loadedAt }),
    ),
    // Keep the last known positions on screen; the next poll may succeed.
    on(LivePositionsActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
  extraSelectors: ({ selectLivePositionsState }) => adapter.getSelectors(selectLivePositionsState),
});
