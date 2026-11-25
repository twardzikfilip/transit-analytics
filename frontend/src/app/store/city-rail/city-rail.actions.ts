import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { CityRailReport } from '../../core/models/models';

export const CityRailActions = createActionGroup({
  source: 'City rail',
  events: {
    Load: emptyProps(),
    Loaded: props<{ report: CityRailReport }>(),
    Failure: props<{ error: string }>(),
  },
});
