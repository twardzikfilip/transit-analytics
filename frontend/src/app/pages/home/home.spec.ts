import { renderPage, signedIn, text } from '../../testing/page-testing';
import { Home } from './home';

describe('Home page', () => {
  it('greets the user and mentions managing vehicles only to admins', () => {
    const guest = renderPage(Home, signedIn('GUEST')).el;
    expect(text(guest.querySelector('h1'))).toBe('Welcome, ann');
    expect(text(guest)).toContain('Browse the fleet.');
  });

  it('tells admins they can manage vehicles', () => {
    expect(text(renderPage(Home, signedIn('ADMIN')).el)).toContain('Browse the fleet and manage vehicles.');
  });
});
