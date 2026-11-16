import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { LineStatsReport } from '../../core/models/models';

export const LineStatsActions = createActionGroup({
  source: 'Line Stats',
  events: {
    Load: emptyProps(),
    Loaded: props<{ report: LineStatsReport }>(),
    Failure: props<{ error: string }>(),
  },
});
