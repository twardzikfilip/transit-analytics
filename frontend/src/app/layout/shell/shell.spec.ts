import { AuthActions } from '../../store/auth/auth.actions';
import { renderPage, signedIn, text } from '../../testing/page-testing';
import { TestBed } from '@angular/core/testing';
import { PageTitle } from '../../core/title/page-title';
import { Shell } from './shell';

describe('Shell', () => {
  it('shows the user, every page in the sidebar, and a menu that logs out', () => {
    const { el, store, render } = renderPage(Shell, signedIn('ADMIN'));

    expect(text(el.querySelector('.user'))).toBe('ann ADMIN');
    expect([...el.querySelectorAll('aside nav a')].map((a) => a.getAttribute('href'))).toContain(
      '/corridors',
    );

    const toggle = el.querySelector('button.hamburger') as HTMLButtonElement;
    toggle.click();
    render();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const logout = [...el.querySelectorAll('[role=menuitem]')].find(
      (i) => text(i) === 'Logout',
    ) as HTMLElement;
    logout.click();
    render();
    expect(store.dispatch).toHaveBeenCalledWith(AuthActions.logout());
    expect(el.querySelector('.dropdown')).toBeNull();
  });

  it('closes the menu on a click elsewhere or Escape', () => {
    const { el, render } = renderPage(Shell, signedIn());
    (el.querySelector('button.hamburger') as HTMLButtonElement).click();
    render();
    document.body.click();
    render();
    expect(el.querySelector('.dropdown')).toBeNull();

    (el.querySelector('button.hamburger') as HTMLButtonElement).click();
    render();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    render();
    expect(el.querySelector('.dropdown')).toBeNull();
  });

  it('groups the pages into Operation and Network, each with an icon, and shows the page title in the header', () => {
    const { el, render } = renderPage(Shell, signedIn());
    expect([...el.querySelectorAll('aside nav h2')].map(text)).toEqual(['Operation', 'Network']);
    const [operation, network] = [...el.querySelectorAll('aside nav ul')];
    expect([...operation.querySelectorAll('a')].map(text)).toEqual([
      'Home',
      'Brigades',
      'Fleet',
      'Abnormal trips',
    ]);
    const hrefs = [...network.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/network-coverage');
    expect(hrefs).toContain('/sources');
    const all = [...el.querySelectorAll('aside nav a')].map((a) => a.getAttribute('href'));
    for (const gone of ['/delays', '/live-map', '/positions']) expect(all).not.toContain(gone);
    expect(el.querySelectorAll('aside nav a app-icon svg path').length).toBeGreaterThan(0);

    TestBed.inject(PageTitle).current.set('Fleet');
    render();
    expect(text(el.querySelector('.topbar h1'))).toBe('Fleet');
  });
});
