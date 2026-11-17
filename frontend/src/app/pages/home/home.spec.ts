import { renderPage, signedIn, text } from '../../testing/page-testing';
import { Home } from './home';

describe('Home page', () => {
  it('greets the user and links to the fleet and the live map', () => {
    const el = renderPage(Home, signedIn('GUEST')).el;
    expect(text(el.querySelector('h1'))).toBe('Welcome, ann');
    expect(text(el)).toContain('You are signed in as GUEST.');
    expect([...el.querySelectorAll('a.card')].map((a) => a.getAttribute('href'))).toEqual([
      '/fleet',
      '/live-map',
    ]);
  });
});
