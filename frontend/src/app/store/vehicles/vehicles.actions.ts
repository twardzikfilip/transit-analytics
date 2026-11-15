import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { Payload, Vehicle } from '../../core/models/models';

export const VehiclesActions = createActionGroup({
  source: 'Vehicles',
  events: {
    Load: emptyProps(),
    'Load Success': props<{ vehicles: Vehicle[] }>(),
    Create: props<{ vehicle: Payload<Vehicle> }>(),
    'Create Success': props<{ vehicle: Vehicle }>(),
    Update: props<{ id: number; vehicle: Payload<Vehicle> }>(),
    'Update Success': props<{ vehicle: Vehicle }>(),
    Delete: props<{ id: number }>(),
    'Delete Success': props<{ id: number }>(),
    Failure: props<{ error: string }>(),
  },
});
