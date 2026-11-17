import { createActionGroup, props } from '@ngrx/store';
import { StatsWindow, VehicleStatsReport } from '../../core/models/models';

export const VehicleStatsActions = createActionGroup({
  source: 'Vehicle Stats',
  events: {
    Load: props<{ window: StatsWindow }>(),
    Loaded: props<{ report: VehicleStatsReport }>(),
    Failure: props<{ error: string }>(),
  },
});
