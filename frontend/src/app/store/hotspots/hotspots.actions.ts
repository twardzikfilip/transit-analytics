import { createActionGroup, props } from '@ngrx/store';
import { HotspotReport, WindowQuery } from '../../core/models/models';

export const HotspotsActions = createActionGroup({
  source: 'Hotspots',
  events: {
    /** `line`: only that route's standstills; null for all routes. */
    Load: props<{ window: WindowQuery; route: string | null }>(),
    Loaded: props<{ report: HotspotReport }>(),
    Failure: props<{ error: string }>(),
  },
});
