import { createActionGroup, props } from '@ngrx/store';
import { TripRunPage, TripRunQuery } from '../../core/models/models';

export const TripRunsActions = createActionGroup({
  source: 'Trip Runs',
  events: {
    Load: props<{ query: TripRunQuery }>(),
    Loaded: props<{ page: TripRunPage }>(),
    Failure: props<{ error: string }>(),
  },
});
