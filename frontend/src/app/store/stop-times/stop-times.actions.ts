import { createActionGroup, props } from '@ngrx/store';
import { Page, StopTime } from '../../core/models/models';
import { PageQuery } from '../paged';

export const StopTimesActions = createActionGroup({
  source: 'Stop Times',
  events: {
    Load: props<{ query: PageQuery }>(),
    'Load Success': props<{ result: Page<StopTime> }>(),
    Failure: props<{ error: string }>(),
  },
});
