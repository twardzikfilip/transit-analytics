import { AuthActions } from '../../store/auth/auth.actions';
import { renderPage, signedIn, text } from '../../testing/page-testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { ApiActivity } from '../../core/api/activity';
import { PageBack } from '../../core/title/page-back';
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

    // The route's title is a translation key, maybe with a value.
    TestBed.inject(PageTitle).route.set('titles.fleetOverview');
    render();
    expect(text(el.querySelector('.topbar h1'))).toBe('Fleet Overview');
    TestBed.inject(PageTitle).route.set('titles.brigade:103-05');
    render();
    expect(text(el.querySelector('.topbar h1'))).toBe('Brigade 103-05');
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
    (el.querySelector('aside nav a[href="/vehicles"]') as HTMLElement).click();
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

  it('shows a progress bar under the header while the app waits for the backend', () => {
    const { el, render } = renderPage(Shell, signedIn());
    const activity = TestBed.inject(ApiActivity);
    expect(el.querySelector('app-activity-bar')).toBeNull();
    activity.start();
    render();
    const bar = el.querySelector('app-activity-bar')!;
    expect(bar.getAttribute('role')).toBe('progressbar');
    expect(bar.previousElementSibling?.matches('header.topbar')).toBe(true);
    activity.end();
    render();
    expect(el.querySelector('app-activity-bar')).toBeNull();
  });

  it('narrows the sidebar to its icons with the button in its header (a desktop), remembered in the browser', () => {
    localStorage.removeItem('sidebar-collapsed');
    const { el, render } = renderPage(Shell, signedIn());
    const aside = el.querySelector('aside')!;
    const toggle = el.querySelector('aside .brand button.collapse-toggle') as HTMLButtonElement;
    expect(aside.classList).not.toContain('collapsed');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('aside nav a')!.getAttribute('title')).toBeNull();

    toggle.click();
    render();
    expect(aside.classList).toContain('collapsed');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    // The button stays, in the sidebar's header, as an icon.
    expect(toggle.closest('.brand')).not.toBeNull();
    expect(text(toggle)).toBe('');
    // Only the icons show; the names are tooltips, and still read by screen readers.
    const home = el.querySelector('aside nav a')!;
    expect(home.getAttribute('title')).toBe('Home');
    expect(text(home.querySelector('.label'))).toBe('Home');
    expect(localStorage.getItem('sidebar-collapsed')).toBe('true');

    // Opened again later: still narrowed.
    TestBed.resetTestingModule();
    const again = renderPage(Shell, signedIn());
    expect(again.el.querySelector('aside')!.classList).toContain('collapsed');
    (again.el.querySelector('aside button.collapse-toggle') as HTMLButtonElement).click();
    again.render();
    expect(again.el.querySelector('aside')!.classList).not.toContain('collapsed');
    localStorage.removeItem('sidebar-collapsed');
  });

  it("puts the page's way back at the very left of the header, as an icon", () => {
    const { el, render } = renderPage(Shell, signedIn());
    expect(el.querySelector('.topbar button.back')).toBeNull();
    const go = vi.fn();
    TestBed.inject(PageBack).link.set({ label: 'Back to all brigades', go });
    render();
    const back = el.querySelector('.topbar button.back') as HTMLButtonElement;
    expect(el.querySelector('.topbar')!.firstElementChild).toBe(back);
    expect(back.getAttribute('aria-label')).toBe('Back to all brigades');
    expect(text(back)).toBe('');
    expect(back.querySelector('app-icon svg')).not.toBeNull();
    back.click();
    expect(go).toHaveBeenCalled();
  });
});
