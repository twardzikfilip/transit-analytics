import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { FleetReport, VehicleDetail, WindowQuery } from '../../core/models/models';

export const FleetActions = createActionGroup({
  source: 'Fleet',
  events: {
    /**
     * `agencies`: the operators whose buses to include; empty for all. `key`: the query as text (queryKey), so a
     * page can tell whether the report it needs is loaded.
     */
    Load: props<{ window: WindowQuery; agencies: string[]; key?: string }>(),
    Loaded: props<{ report: FleetReport }>(),
    Failure: props<{ error: string }>(),
    'Load Vehicle': props<{ id: number; window: WindowQuery }>(),
    'Vehicle Loaded': props<{ vehicle: VehicleDetail }>(),
    'Vehicle Failure': props<{ error: string }>(),
    'Clear Vehicle': emptyProps(),
  },
});
