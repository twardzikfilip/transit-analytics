import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { AppUser, NewUser } from '../../core/models/models';

export const UsersActions = createActionGroup({
  source: 'Users',
  events: {
    Load: emptyProps(),
    Loaded: props<{ users: AppUser[] }>(),
    Create: props<{ user: NewUser }>(),
    Created: props<{ user: AppUser }>(),
    Delete: props<{ username: string }>(),
    Deleted: props<{ username: string }>(),
    Failure: props<{ error: string }>(),
  },
});
