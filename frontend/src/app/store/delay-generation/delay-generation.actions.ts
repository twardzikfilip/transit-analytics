import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { DelayGenerationReport, DelayRouteReport, WindowQuery } from '../../core/models/models';

export const DelayGenerationActions = createActionGroup({
  source: 'Delay Generation',
  events: {
    Load: props<{ window: WindowQuery }>(),
    Loaded: props<{ report: DelayGenerationReport }>(),
    Failure: props<{ error: string }>(),
    'Load Route': props<{ window: WindowQuery; route: string }>(),
    'Route Loaded': props<{ report: DelayRouteReport }>(),
    'Route Failure': props<{ error: string }>(),
    'Clear Route': emptyProps(),
  },
});
