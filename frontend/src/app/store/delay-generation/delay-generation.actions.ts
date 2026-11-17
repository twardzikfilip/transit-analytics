import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { DelayGenerationReport, DelayLineReport, StatsWindow } from '../../core/models/models';

export const DelayGenerationActions = createActionGroup({
  source: 'Delay Generation',
  events: {
    Load: props<{ window: StatsWindow }>(),
    Loaded: props<{ report: DelayGenerationReport }>(),
    Failure: props<{ error: string }>(),
    'Load Line': props<{ window: StatsWindow; line: string }>(),
    'Line Loaded': props<{ report: DelayLineReport }>(),
    'Line Failure': props<{ error: string }>(),
    'Clear Line': emptyProps(),
  },
});
