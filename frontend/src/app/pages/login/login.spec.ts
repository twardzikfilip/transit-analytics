import { AuthActions } from '../../store/auth/auth.actions';
import { renderPage, text } from '../../testing/page-testing';
import { Login } from './login';

describe('Login page', () => {
  const auth = (loading: boolean, error: string | null) => ({
    auth: { session: null, loading, error },
  });

  it('signs in with the typed credentials, but not with an empty form', () => {
    const { el, store, render } = renderPage(Login, auth(false, null));
    const [user, password] = [...el.querySelectorAll('input')] as HTMLInputElement[];
    const form = el.querySelector('form')!;

    form.dispatchEvent(new Event('submit'));
    expect(store.dispatch).not.toHaveBeenCalled();

    user.value = 'ann';
    user.dispatchEvent(new Event('input'));
    password.value = 'secret';
    password.dispatchEvent(new Event('input'));
    form.dispatchEvent(new Event('submit'));
    render();
    expect(store.dispatch).toHaveBeenCalledWith(
      AuthActions.login({ username: 'ann', password: 'secret' }),
    );
  });

  it('shows progress and the error from the last attempt', () => {
    expect(text(renderPage(Login, auth(true, null)).el.querySelector('button'))).toBe(
      'Signing in…',
    );
  });

  it('leaves a failed attempt to the toast: nothing of it on the form', () => {
    const { el } = renderPage(Login, auth(false, 'Invalid username or password'));
    expect(text(el)).not.toContain('Invalid username or password');
    expect(el.querySelector('.error')).toBeNull();
  });
});
