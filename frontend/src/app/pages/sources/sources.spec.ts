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

  it('links the route types article, which the Routes page classifies lines by', () => {
    const el = renderPage(Sources, {}).el;
    const link = el.querySelector<HTMLAnchorElement>('a[href*="alternativetransport"]');
    expect(text(link)).toBe('Alternative Transport: route types');
    const row = link!.closest('tr')!;
    expect(text(row)).toContain('route geometries');
    expect(text(row.querySelectorAll('td')[2])).toBe('Routes');
  });

  it('links the timetables of the other operators', () => {
    const el = renderPage(Sources, {}).el;
    const names = [...el.querySelectorAll('tbody tr td:first-child')].map(text);
    expect(names).toEqual(
      expect.arrayContaining([
        'PKS Nova line 500 (GTFS)',
        'Wschód Express lines 400–476 (GTFS)',
        'Turośń Kościelna line 700 (GTFS)',
      ]),
    );
    expect(el.querySelector('a[href$="wschod_express.zip"]')).not.toBeNull();
    expect(el.querySelector('a[href$="turosn_koscielna.zip"]')).not.toBeNull();
  });

  it('says which area zbiorkom.live is recorded in, under its source', () => {
    const el = renderPage(Sources, {}).el;
    const row = el.querySelector('a[href="https://zbiorkom.live"]')!.closest('tr')!;
    const detail = row.querySelector('.detail');
    expect(text(detail?.querySelector('code'))).toBe('22.45,52.88,23.48,53.44');
    expect(text(detail)).toContain('Kołodzieje (line 470)');
  });

  it('credits the photo behind the login form', () => {
    const el = renderPage(Sources, {}).el;
    const link = el.querySelector<HTMLAnchorElement>('a[href*="radio.bialystok.pl"]');
    expect(text(link)).toBe('Polskie Radio Białystok: photo');
    expect(text(link!.closest('tr')!)).toContain('Joanna Szubzda');
  });
});
