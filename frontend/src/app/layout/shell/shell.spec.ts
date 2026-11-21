import { AuthActions } from '../../store/auth/auth.actions';
import { renderPage, signedIn, text } from '../../testing/page-testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
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

  it('puts Home first, then groups the pages into Operation and Network, each with an icon, and shows the page title in the header', () => {
    const { el, render } = renderPage(Shell, signedIn());
    expect([...el.querySelectorAll('aside nav h2')].map(text)).toEqual(['Operation', 'Network']);
    const [home, operation, network] = [...el.querySelectorAll('aside nav ul')];
    expect([...home.querySelectorAll('a')].map(text)).toEqual(['Home']);
    expect([...operation.querySelectorAll('a')].map(text)).toEqual([
      'Brigades',
      'Fleet',
      'Abnormal trips',
    ]);
    const hrefs = [...network.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/network-coverage');
    expect(hrefs).toContain('/sources');
    const all = [...el.querySelectorAll('aside nav a')].map((a) => a.getAttribute('href'));
    for (const gone of ['/delays', '/live-map', '/positions', '/lines'])
      expect(all).not.toContain(gone);
    expect(el.querySelectorAll('aside nav a app-icon svg path').length).toBeGreaterThan(0);

    TestBed.inject(PageTitle).current.set('Fleet');
    render();
    expect(text(el.querySelector('.topbar h1'))).toBe('Fleet');
  });

  it('shows and hides the sidebar with the header button (a phone), and hides it on a link, a tap beside it or Escape', () => {
    const { el, render } = renderPage(Shell, signedIn());
    const aside = el.querySelector('aside') as HTMLElement;
    const toggle = el.querySelector('button.nav-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-controls')).toBe(aside.id);
    expect(aside.classList).not.toContain('open');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    render();
    expect(aside.classList).toContain('open');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Hide pages');

    // Choosing a page hides it (the navigation itself isn't part of this test).
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    (el.querySelector('aside nav a[href="/fleet"]') as HTMLElement).click();
    render();
    expect(aside.classList).not.toContain('open');

    // So does a tap beside it.
    toggle.click();
    render();
    (el.querySelector('.backdrop') as HTMLElement).click();
    render();
    expect(aside.classList).not.toContain('open');
    expect(el.querySelector('.backdrop')).toBeNull();

    // Its own close button, and Escape.
    toggle.click();
    render();
    (el.querySelector('aside button.nav-close') as HTMLButtonElement).click();
    render();
    expect(aside.classList).not.toContain('open');
    toggle.click();
    render();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    render();
    expect(aside.classList).not.toContain('open');
  });
});
