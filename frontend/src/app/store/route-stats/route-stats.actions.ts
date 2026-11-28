import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { RouteStatsReport } from '../../core/models/models';

export const RouteStatsActions = createActionGroup({
  source: 'Line Stats',
  events: {
    Load: emptyProps(),
    Loaded: props<{ report: RouteStatsReport }>(),
    Failure: props<{ error: string }>(),
  },
});
