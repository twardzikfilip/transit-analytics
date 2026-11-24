import { createActionGroup, props } from '@ngrx/store';
import { TripRunDay, TripRunPage, TripRunQuery } from '../../core/models/models';

export const TripRunsActions = createActionGroup({
  source: 'Trip Runs',
  events: {
    Load: props<{ query: TripRunQuery }>(),
    Loaded: props<{ page: TripRunPage }>(),
    Failure: props<{ error: string }>(),
    'Load Day': props<{ brigade: string; date: string | null }>(),
    'Day Loaded': props<{ day: TripRunDay }>(),
    'Day Failure': props<{ error: string }>(),
  },
});
