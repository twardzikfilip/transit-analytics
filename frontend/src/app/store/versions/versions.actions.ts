import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { Versions } from '../../core/models/models';

export const VersionsActions = createActionGroup({
  source: 'Versions',
  events: {
    Load: emptyProps(),
    Loaded: props<{ versions: Versions }>(),
    Failure: props<{ error: string }>(),
  },
});
