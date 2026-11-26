import { CityRailReport } from '../../core/models/models';
import { CityRailActions } from '../../store/city-rail/city-rail.actions';
import { cityRailFeature } from '../../store/city-rail/city-rail.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { CityRail } from './city-rail';

const HUB = { name: 'Białystok', lat: 53.1336, lon: 23.1354 };

/** With PKP Intercity. */
const ALL = {
  destinations: ['Warszawa'],
  departures: [
    { fromCity: 20, toCity: 21 },
    { fromCity: 12, toCity: 13 },
  ],
};

const REPORT: CityRailReport = {
  railways: [
    {
      ref: '6',
      spur: false,
      points: [
        [53.1, 23.1],
        [53.2, 23.2],
      ],
    },
    {
      ref: null,
      spur: true,
      points: [
        [53.1, 23.1],
        [53.1, 23.12],
      ],
    },
  ],
  days: [
    { kind: 'weekday', date: '2026-11-25' },
    { kind: 'saturday', date: '2026-11-28' },
  ],
  routes: [
    {
      id: 'Czarny Blok',
      stations: [HUB, { name: 'Wasilków', lat: 53.1979, lon: 23.1768 }],
      points: [
        [53.1336, 23.1354],
        [53.2, 23.2],
      ],
      polregio: {
        destinations: ['Kuźnica Białostocka', 'Suwałki'],
        departures: [
          { fromCity: 9, toCity: 10 },
          { fromCity: 7, toCity: 6 },
        ],
      },
      all: ALL,
    },
    {
      id: 'Lewickie',
      stations: [HUB],
      points: [
        [53.1336, 23.1354],
        [53.03, 23.12],
      ],
      polregio: {
        destinations: ['Hajnówka'],
        departures: [
          { fromCity: 8, toCity: 8 },
          { fromCity: 4, toCity: 4 },
        ],
      },
      all: ALL,
    },
  ],
};

const state = (report: CityRailReport) =>
  stateAfter(cityRailFeature, CityRailActions.loaded({ report }));

async function render(report: CityRailReport, query: Record<string, string> = {}) {
  useSvgMaps();
  const page = renderPage(CityRail, state(report), query);
  await page.fixture.whenStable();
  page.fixture.detectChanges();
  return page;
}

/** The routes' lines on the map, in their colours: [colour, width, opacity]. */
const lines = (el: HTMLElement) =>
  [...el.querySelectorAll('.leaflet-overlay-pane path')]
    .filter((p) => ['#2a78d6', '#eb6834'].includes(p.getAttribute('stroke') ?? ''))
    .map((p) => ['stroke', 'stroke-width', 'stroke-opacity'].map((a) => p.getAttribute(a)));

describe('City rail page', () => {
  it('lists the routes with their trains both ways, in the colours of the map', async () => {
    const { el, store } = await render(REPORT);
    expect(store.dispatch).toHaveBeenCalledWith(CityRailActions.load());
    const [polregio, all] = [...el.querySelectorAll('table')];
    const rows = [...polregio.querySelectorAll('tbody tr')] as HTMLElement[];
    expect(rows.map((r) => text(r.querySelector('.name')!))).toEqual([
      'Białystok – Czarny Blok',
      'Białystok – Lewickie',
    ]);
    expect(rows.map((r) => [...r.querySelectorAll('td.num')].map(text))).toEqual([
      ['9', '10', '7', '6'],
      ['8', '8', '4', '4'],
    ]);
    expect([...polregio.querySelectorAll('tfoot td.num')].map(text)).toEqual([
      '17',
      '18',
      '11',
      '10',
    ]);
    expect([...all.querySelectorAll('tfoot td.num')].map(text)).toEqual(['40', '42', '24', '26']);
    expect(text(all)).toContain('Warszawa');
    expect(text(rows[0])).toContain('Kuźnica Białostocka, Suwałki');
    expect(lines(el)).toEqual([
      ['#2a78d6', '5', '1'],
      ['#eb6834', '5', '1'],
    ]);
  });

  it('highlights the route pointed at', async () => {
    const { el, fixture } = await render(REPORT);
    const row = el.querySelectorAll('tbody tr')[1];
    row.dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    // Drawn last: on top.
    expect(lines(el)).toEqual([
      ['#2a78d6', '5', '0.3'],
      ['#eb6834', '8', '1'],
    ]);
    row.dispatchEvent(new Event('mouseleave'));
    fixture.detectChanges();
    expect(lines(el).map((l) => l[1])).toEqual(['5', '5']);
  });

  it('highlights the route chosen in the URL', async () => {
    const { el } = await render(REPORT, { route: 'Czarny Blok' });
    expect(lines(el).map((l) => l[1])).toEqual(['5', '8']);
    expect(el.querySelector('tbody .name')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('says so while the trains are not loaded', async () => {
    const { el } = await render({ ...REPORT, days: [], routes: [] });
    expect(el.querySelector('table')).toBeNull();
    expect(text(el)).toContain('not loaded yet');
  });
});
