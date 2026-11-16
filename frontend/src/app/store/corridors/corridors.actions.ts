import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { CorridorReport } from '../../core/models/models';

export const CorridorsActions = createActionGroup({
  source: 'Corridors',
  events: {
    Load: emptyProps(),
    Loaded: props<{ report: CorridorReport }>(),
    Failure: props<{ error: string }>(),
  },
});
