import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { RouteDetail, RoutePerformance, RouteRef } from '../../core/models/models';

export const RouteDetailActions = createActionGroup({
  source: 'Route Detail',
  events: {
    /** Loads the routes to pick from. */
    'Load Routes': emptyProps(),
    'Routes Loaded': props<{ routes: RouteRef[] }>(),
    /** Loads a route's timetable detail and, separately (it takes longer), its measured performance. */
    Select: props<{ route: string }>(),
    'Detail Loaded': props<{ detail: RouteDetail }>(),
    'Performance Loaded': props<{ route: string; performance: RoutePerformance }>(),
    Failure: props<{ error: string }>(),
  },
});
