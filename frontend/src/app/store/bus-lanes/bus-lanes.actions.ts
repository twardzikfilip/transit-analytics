import { createActionGroup, props } from '@ngrx/store';
import { BusLaneReport, WindowQuery } from '../../core/models/models';

export const BusLanesActions = createActionGroup({
  source: 'Bus lanes',
  events: {
    Load: props<{ window: WindowQuery }>(),
    Loaded: props<{ report: BusLaneReport }>(),
    Failure: props<{ error: string }>(),
  },
});
