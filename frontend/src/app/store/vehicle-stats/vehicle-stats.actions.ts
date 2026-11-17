import { createActionGroup, props } from '@ngrx/store';
import { StatsWindow, VehicleStatsReport } from '../../core/models/models';

export const VehicleStatsActions = createActionGroup({
  source: 'Vehicle Stats',
  events: {
    /** The operators whose vehicles to include in `agencies`; empty for all. */
    Load: props<{ window: StatsWindow; agencies: string[] }>(),
    Loaded: props<{ report: VehicleStatsReport }>(),
    Failure: props<{ error: string }>(),
  },
});
