import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { Route } from '../../core/models/models';

export const RoutesActions = createActionGroup({
  source: 'Routes',
  events: {
    Load: emptyProps(),
    'Load Success': props<{ routes: Route[] }>(),
    Failure: props<{ error: string }>(),
  },
});
