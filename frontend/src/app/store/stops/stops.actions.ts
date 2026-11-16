import { createActionGroup, props } from '@ngrx/store';
import { Page, Stop } from '../../core/models/models';
import { PageQuery } from '../paged';

export const StopsActions = createActionGroup({
  source: 'Stops',
  events: {
    Load: props<{ query: PageQuery }>(),
    'Load Success': props<{ result: Page<Stop> }>(),
    Failure: props<{ error: string }>(),
  },
});
