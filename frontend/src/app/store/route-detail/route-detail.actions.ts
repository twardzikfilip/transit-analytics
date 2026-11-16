import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { LineDetail, LinePerformance, LineRef } from '../../core/models/models';

export const RouteDetailActions = createActionGroup({
  source: 'Route Detail',
  events: {
    /** Loads the lines to pick from. */
    'Load Lines': emptyProps(),
    'Lines Loaded': props<{ lines: LineRef[] }>(),
    /** Loads a line's timetable detail and, separately (it takes longer), its measured performance. */
    Select: props<{ line: string }>(),
    'Detail Loaded': props<{ detail: LineDetail }>(),
    'Performance Loaded': props<{ line: string; performance: LinePerformance }>(),
    Failure: props<{ error: string }>(),
  },
});
