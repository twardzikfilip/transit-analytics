import { CityRailReport, RailBusComparison } from '../../core/models/models';
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

const leg = (route: string, from: string, to: string, departure: string, arrival: string) => ({
  route,
  from,
  to,
  departure,
  arrival,
});

const COMPARISON: RailBusComparison = {
  day: '2026-11-26',
  offPeak: ['08:30-15:00', '17:00-20:00'],
  maxChanges: 2,
  stations: [
    {
      name: 'Białystok',
      stops: [
        { name: 'Centrum Przydworcowe', meters: 54 },
        { name: 'Kolejowa', meters: 163 },
      ],
    },
    { name: 'Białystok Starosielce', stops: [{ name: 'Starosielce PKP', meters: 62 }] },
    { name: 'Białystok Bacieczki', stops: [] },
  ],
  pairs: [
    {
      from: 'Białystok',
      to: 'Białystok Starosielce',
      train: {
        minutes: 6,
        departure: '05:40',
        arrival: '05:46',
        legs: [leg('15801', 'Białystok', 'Białystok Starosielce', '05:40', '05:46')],
      },
      bus: {
        minutes: 15,
        departure: '09:04',
        arrival: '09:19',
        legs: [
          leg('20', 'Centrum Przydworcowe', 'Popiełuszki', '09:04', '09:10'),
          leg('8', 'Popiełuszki', 'Starosielce PKP', '09:12', '09:19'),
        ],
      },
    },
    { from: 'Białystok', to: 'Białystok Bacieczki', train: null, bus: null },
    {
      from: 'Białystok Starosielce',
      to: 'Białystok',
      train: null,
      bus: {
        minutes: 15,
        departure: '17:57',
        arrival: '18:12',
        legs: [leg('8', 'Starosielce PKP', 'Kolejowa', '17:57', '18:12')],
      },
    },
  ],
};

const state = (report: CityRailReport, comparison: RailBusComparison | null = COMPARISON) =>
  stateAfter(
    cityRailFeature,
    CityRailActions.loaded({ report }),
    ...(comparison ? [CityRailActions.comparisonLoaded({ comparison })] : []),
  );

async function render(
  report: CityRailReport,
  query: Record<string, string> = {},
  comparison: RailBusComparison | null = COMPARISON,
) {
  useSvgMaps();
  const page = renderPage(CityRail, state(report, comparison), query);
  await page.fixture.whenStable();
  page.fixture.detectChanges();
  return page;
}

/** The routes' routes on the map, in their colours: [colour, width, opacity]. */
const routes = (el: HTMLElement) =>
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
    expect(routes(el)).toEqual([
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
    expect(routes(el)).toEqual([
      ['#2a78d6', '5', '0.3'],
      ['#eb6834', '8', '1'],
    ]);
    row.dispatchEvent(new Event('mouseleave'));
    fixture.detectChanges();
    expect(routes(el).map((l) => l[1])).toEqual(['5', '5']);
  });

  it('highlights the route chosen in the URL', async () => {
    const { el } = await render(REPORT, { route: 'Czarny Blok' });
    expect(routes(el).map((l) => l[1])).toEqual(['5', '8']);
    expect(el.querySelector('tbody .name')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('says so while the trains are not loaded', async () => {
    const { el } = await render({ ...REPORT, days: [], routes: [] }, {}, null);
    expect(el.querySelector('table')).toBeNull();
    expect(text(el)).toContain('not loaded yet');
  });

  it('compares the train with the bus between the stations in the city', async () => {
    const { el, store } = await render(REPORT);
    expect(store.dispatch).toHaveBeenCalledWith(CityRailActions.loadComparison());
    const table = el.querySelector('app-rail-bus-table table')!;
    const rows = [...table.querySelectorAll('tbody tr')];
    expect(rows.map((r) => text(r.querySelector('th.from')))).toEqual([
      'Białystok',
      '',
      'Białystok Starosielce',
    ]);
    expect(table.querySelector('th.from')!.getAttribute('rowspan')).toBe('2');

    const [train, bus] = [...rows[0].querySelectorAll('td.journey')];
    expect(text(train.querySelector('.minutes'))).toBe('6 min');
    expect(train.classList).toContain('faster');
    expect(bus.classList).not.toContain('faster');
    expect(text(bus.querySelector('.legs'))).toBe('20 → 8 · 09:04–09:19');
    expect(bus.querySelector('.legs')!.getAttribute('title')).toBe(
      '09:04 Centrum Przydworcowe → 09:10 Popiełuszki (20)\n' +
        '09:12 Popiełuszki → 09:19 Starosielce PKP (8)',
    );
    expect(text(rows[0].querySelector('.difference'))).toBe('+9 min');

    expect(text(rows[1])).toContain('No connection');
    expect(text(rows[1].querySelector('.difference'))).toBe('');
    // Only by bus: the bus is the faster way.
    expect(rows[2].querySelectorAll('td.journey')[1].classList).toContain('faster');

    const stops = text(el.querySelector('app-rail-bus-table details'));
    expect(stops).toContain('Białystok: Centrum Przydworcowe (54 m), Kolejowa (163 m)');
    expect(stops).toContain('Białystok Bacieczki: none within 1 km');
  });

  it('shows a skeleton while the comparison loads', async () => {
    useSvgMaps();
    const { el, fixture } = renderPage(
      CityRail,
      stateAfter(
        cityRailFeature,
        CityRailActions.loaded({ report: REPORT }),
        CityRailActions.loadComparison(),
      ),
    );
    await fixture.whenStable();
    expect(el.querySelector('app-rail-bus-table')).toBeNull();
    expect(text(el)).toContain('Train or bus in the city');
    expect(el.querySelectorAll('app-skeleton').length).toBe(1);
  });

  it('says so when there are no trains to compare', async () => {
    const { el } = await render(REPORT, {}, { ...COMPARISON, day: null, stations: [], pairs: [] });
    expect(el.querySelector('app-rail-bus-table')).toBeNull();
    expect(text(el)).toContain('not loaded yet');
  });
});
