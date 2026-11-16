import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { LivePosition } from '../../core/models/models';

export const LivePositionsActions = createActionGroup({
  source: 'Live Positions',
  events: {
    Load: emptyProps(),
    'Load Success': props<{ positions: LivePosition[]; loadedAt: string }>(),
    Failure: props<{ error: string }>(),
  },
});
