import { createActionGroup, props } from '@ngrx/store';
import { ReplayResponse, RouteActivity } from '../../core/models/models';

/** A time window ([from, to), ISO instants) and the lines to show (empty = all). */
export interface ReplayQuery {
  /** Null for all time. */
  from: string | null;
  to: string;
  routes: string[];
}

export const DelayReplayActions = createActionGroup({
  source: 'Delay Replay',
  events: {
    /** Loads the replay frames and the lines recorded in the window. */
    Load: props<{ query: ReplayQuery }>(),
    'Replay Loaded': props<{ replay: ReplayResponse }>(),
    'Routes Loaded': props<{ routes: RouteActivity[] }>(),
    Failure: props<{ error: string }>(),
  },
});
