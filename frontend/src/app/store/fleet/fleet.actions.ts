import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { FleetReport, VehicleDetail, WindowQuery } from '../../core/models/models';

export const FleetActions = createActionGroup({
  source: 'Fleet',
  events: {
    /** `agencies`: the operators whose buses to include; empty for all. */
    Load: props<{ window: WindowQuery; agencies: string[] }>(),
    Loaded: props<{ report: FleetReport }>(),
    Failure: props<{ error: string }>(),
    'Load Vehicle': props<{ id: number; window: WindowQuery }>(),
    'Vehicle Loaded': props<{ vehicle: VehicleDetail }>(),
    'Vehicle Failure': props<{ error: string }>(),
    'Clear Vehicle': emptyProps(),
  },
});
