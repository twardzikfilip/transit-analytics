import { createActionGroup, props } from '@ngrx/store';
import { HotspotReport, WindowQuery } from '../../core/models/models';

export const HotspotsActions = createActionGroup({
  source: 'Hotspots',
  events: {
    /** `line`: only that line's standstills; null for all lines. */
    Load: props<{ window: WindowQuery; line: string | null }>(),
    Loaded: props<{ report: HotspotReport }>(),
    Failure: props<{ error: string }>(),
  },
});
