import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { Session } from '../../core/models/models';

export const AuthActions = createActionGroup({
  source: 'Auth',
  events: {
    Login: props<{ username: string; password: string }>(),
    'Login Success': props<{ session: Session }>(),
    'Login Failure': props<{ error: string }>(),
    Logout: emptyProps(),
  },
});
