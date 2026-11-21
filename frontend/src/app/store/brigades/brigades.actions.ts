import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { BrigadeDay, BrigadeList } from '../../core/models/models';

export const BrigadesActions = createActionGroup({
  source: 'Brigades',
  events: {
    /** Every brigade of an agency; null: the city's. */
    'Load List': props<{ agency: string | null }>(),
    'List Loaded': props<{ list: BrigadeList }>(),
    'Load Day': props<{ brigade: string; date: string | null }>(),
    'Day Loaded': props<{ day: BrigadeDay }>(),
    'Clear Day': emptyProps(),
    Failure: props<{ error: string }>(),
  },
});
