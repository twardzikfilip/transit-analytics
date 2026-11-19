import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { BrigadeDay, BrigadeDayList } from '../../core/models/models';

export const BrigadesActions = createActionGroup({
  source: 'Brigades',
  events: {
    /** The brigades of an agency on a day; nulls: today's, the city's. */
    'Load List': props<{ date: string | null; agency: string | null }>(),
    'List Loaded': props<{ list: BrigadeDayList }>(),
    'Load Day': props<{ brigade: string; date: string | null }>(),
    'Day Loaded': props<{ day: BrigadeDay }>(),
    'Clear Day': emptyProps(),
    Failure: props<{ error: string }>(),
  },
});
