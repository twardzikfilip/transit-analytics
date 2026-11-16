import { createActionGroup, props } from '@ngrx/store';
import { Page, Trip } from '../../core/models/models';
import { PageQuery } from '../paged';

export const TripsActions = createActionGroup({
  source: 'Trips',
  events: {
    Load: props<{ query: PageQuery }>(),
    'Load Success': props<{ result: Page<Trip> }>(),
    Failure: props<{ error: string }>(),
  },
});
