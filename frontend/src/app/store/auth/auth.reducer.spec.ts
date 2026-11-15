import { AuthActions } from './auth.actions';
import { authFeature } from './auth.reducer';

describe('auth reducer', () => {
  const session = { token: 't', username: 'admin', role: 'ADMIN' as const };
  const reduce = authFeature.reducer;

  it('stores the session on login success and clears it on logout', () => {
    let state = reduce(undefined, AuthActions.login({ username: 'admin', password: 'x' }));
    expect(state.loading).toBe(true);

    state = reduce(state, AuthActions.loginSuccess({ session }));
    expect(state.session).toEqual(session);
    expect(authFeature.selectIsAdmin.projector(state.session)).toBe(true);

    state = reduce(state, AuthActions.logout());
    expect(state.session).toBeNull();
  });

  it('keeps the error on login failure', () => {
    const state = reduce(undefined, AuthActions.loginFailure({ error: 'nope' }));
    expect(state.error).toBe('nope');
    expect(state.loading).toBe(false);
  });
});
