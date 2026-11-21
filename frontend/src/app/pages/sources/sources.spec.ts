import { renderPage, text } from '../../testing/page-testing';
import { Sources } from './sources';

describe('Sources page', () => {
  it('lists each source with what it gives and where it is used', () => {
    const el = renderPage(Sources, {}).el;
    const rows = [...el.querySelectorAll('tbody tr')];
    expect(rows.length).toBeGreaterThan(5);
    expect(rows.map((r) => text(r.querySelector('td')))).toContain('zbiorkom.live');
    expect(rows.every((r) => r.querySelectorAll('td').length === 4)).toBe(true);
  });
});
