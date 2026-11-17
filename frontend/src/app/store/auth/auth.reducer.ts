import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { Session } from '../../core/models/models';
import { AuthService } from '../../core/auth/auth.service';
import { AuthActions } from './auth.actions';

export interface AuthState {
  session: Session | null;
  loading: boolean;
  error: string | null;
}

const initialState: AuthState = {
  session: AuthService.restoreSession(),
  loading: false,
  error: null,
};

export const authFeature = createFeature({
  name: 'auth',
  reducer: createReducer(
    initialState,
    on(AuthActions.login, (state) => ({ ...state, loading: true, error: null })),
    on(AuthActions.loginSuccess, (_, { session }) => ({ session, loading: false, error: null })),
    on(AuthActions.loginFailure, (state, { error }) => ({ ...state, loading: false, error })),
    on(AuthActions.logout, () => ({ session: null, loading: false, error: null })),
  ),
  extraSelectors: ({ selectSession }) => ({
    selectToken: createSelector(selectSession, (s) => s?.token ?? null),
    selectUsername: createSelector(selectSession, (s) => s?.username ?? null),
    selectRole: createSelector(selectSession, (s) => s?.role ?? null),
    selectIsAuthenticated: createSelector(selectSession, (s) => s !== null),
  }),
});
