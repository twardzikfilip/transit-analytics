import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { BrigadeApi } from '../core/api/api.services';
import {
  CorridorReport,
  LineDetail,
  LinePerformance,
  LineStatsReport,
  LivePosition,
} from '../core/models/models';
import { CorridorsActions } from '../store/corridors/corridors.actions';
import { corridorsFeature } from '../store/corridors/corridors.reducer';
import { LivePositionsActions } from '../store/live-positions/live-positions.actions';
import { livePositionsFeature } from '../store/live-positions/live-positions.reducer';
import { PositionHistoryActions } from '../store/position-history/position-history.actions';
import { positionHistoryFeature } from '../store/position-history/position-history.reducer';
import { RouteDetailActions } from '../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../store/route-detail/route-detail.reducer';
import { renderPage, stateAfter, useSvgMaps, text } from '../testing/page-testing';
import { LineStatsActions } from '../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../store/line-stats/line-stats.reducer';
import { Corridors } from './corridors/corridors';
import { LiveMap } from './home/live-map/live-map';
import { PositionHistory } from './home/position-history/position-history';
import { RoutesPage } from './routes/routes';

/** A compact replay row: vehicle, latitude, longitude, delay, line and brigade indexes. */
type ReplayRow = [number, number, number, number | null, number | null, number | null];

afterEach(() => {
  // Destroy the pages (and their maps) first: Leaflet keeps redrawing on animation frames until a map is removed.
  TestBed.resetTestingModule();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('Important corridors page', () => {
  it('loads the report, counts one-way corridors and shows the maps and the table', () => {
    useSvgMaps();
    const corridor = (from: string, oneWay: boolean) => ({
      from,
      to: 'Z',
      stops: [from, 'B', 'C', 'Z'],
      lines: ['1', '2'],
      throughLines: ['1', '2'],
      km: 1,
      buses: 300,
      oneWay,
      peakIntervalMin: 4,
      offPeakIntervalMin: 6,
      peakCv: 0.5,
      offPeakCv: 0.6,
    });
    const report: CorridorReport = {
      serviceId: 'P_1349',
      peak: [{ from: '06:30', to: '08:30' }],
      offPeak: [{ from: '08:30', to: '15:00' }],
      corridors: [corridor('A', false), corridor('K', true)],
      segments: [
        {
          from: 'A',
          to: 'B',
          peakBuses: 40,
          buses: 300,
          points: [
            [53.1, 23.1],
            [53.11, 23.12],
          ],
        },
      ],
    };
    const { el, store } = renderPage(
      Corridors,
      stateAfter(corridorsFeature, CorridorsActions.loaded({ report })),
    );

    expect(store.dispatch).toHaveBeenCalledWith(CorridorsActions.load());
    expect(text(el.querySelector('.summary'))).toBe(
      '2 corridors · 1 run one way only (the lines come back along other streets)',
    );
    expect(el.querySelectorAll('app-load-map')).toHaveLength(2);
    expect(el.querySelectorAll('app-corridor-table tbody tr')).toHaveLength(2);
    expect(text(el.querySelector('.how'))).toContain('A ≤ 0.21');
  });
});

describe('Position history (Home)', () => {
  it('loads the window, line and operator in the URL, and the operators once', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store } = renderPage(PositionHistory, stateAfter(positionHistoryFeature), {
      range: '1h',
      route: '103',
      agency: 'TUROSN',
    });

    expect(store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.load({
        query: {
          from: '2026-11-16T11:00:40.000Z',
          to: '2026-11-16T12:00:40.000Z',
          route: '103',
          agency: 'TUROSN',
        },
      }),
    );
    expect(store.dispatch).toHaveBeenCalledWith(PositionHistoryActions.loadAgencies());
  });

  it('shows the city operator unless another one, or all, is chosen', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const city = renderPage(PositionHistory, stateAfter(positionHistoryFeature), { range: '1h' });
    expect(city.store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.load({
        query: {
          from: '2026-11-16T11:00:40.000Z',
          to: '2026-11-16T12:00:40.000Z',
          route: null,
          agency: 'BKM',
        },
      }),
    );
  });

  it('shows every operator when all are chosen', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const all = renderPage(PositionHistory, stateAfter(positionHistoryFeature), {
      range: '1h',
      agency: 'all',
    });
    expect(all.store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.load({
        query: {
          from: '2026-11-16T11:00:40.000Z',
          to: '2026-11-16T12:00:40.000Z',
          route: null,
          agency: null,
        },
      }),
    );
  });

  it('refreshes a short preset window each poll', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store } = renderPage(PositionHistory, stateAfter(positionHistoryFeature), {
      range: '5m',
    });
    expect(store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.load({
        query: {
          from: '2026-11-16T11:55:40.000Z',
          to: '2026-11-16T12:00:40.000Z',
          route: null,
          agency: 'BKM',
        },
      }),
    );
    const before = vi.mocked(store.dispatch).mock.calls.length;
    vi.advanceTimersByTime(10_000);
    expect(vi.mocked(store.dispatch).mock.calls.length).toBe(before + 1);
  });

  it('refreshes a long preset window every minute', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const preset = renderPage(PositionHistory, stateAfter(positionHistoryFeature), { range: '6h' });
    const before = vi.mocked(preset.store.dispatch).mock.calls.length;
    vi.advanceTimersByTime(10_000);
    expect(vi.mocked(preset.store.dispatch).mock.calls.length).toBe(before);
    vi.advanceTimersByTime(50_000);
    expect(vi.mocked(preset.store.dispatch).mock.calls.length).toBe(before + 1);
  });

  it('loads a window chosen after the first refresh at once', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store, setQuery } = renderPage(PositionHistory, stateAfter(positionHistoryFeature), {
      range: '6h',
    });
    vi.advanceTimersByTime(25_000);
    setQuery({ from: '2026-11-16T08:00', to: '2026-11-16T10:00' });
    expect(store.dispatch).toHaveBeenLastCalledWith(
      PositionHistoryActions.load({
        query: {
          from: new Date('2026-11-16T08:00').toISOString(),
          to: new Date('2026-11-16T10:00').toISOString(),
          route: null,
          agency: 'BKM',
        },
      }),
    );
    setQuery({ range: '12h' });
    expect(vi.mocked(store.dispatch).mock.lastCall![0]).toMatchObject({
      query: { to: '2026-11-16T12:01:00.000Z', from: '2026-11-16T00:01:00.000Z' },
    });
  });

  it('never reloads a custom window', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const custom = renderPage(PositionHistory, stateAfter(positionHistoryFeature), {
      from: '2026-11-16T08:00',
      to: '2026-11-16T10:00',
    });
    const loads = vi.mocked(custom.store.dispatch).mock.calls.length;
    vi.advanceTimersByTime(120_000);
    expect(vi.mocked(custom.store.dispatch).mock.calls.length).toBe(loads);
  });

  it('shows four delay bands', () => {
    const { el } = renderPage(
      PositionHistory,
      stateAfter(
        positionHistoryFeature,
        PositionHistoryActions.delaysLoaded({
          delays: [
            { fromMinutes: null, toMinutes: 2, vehicles: 90, reports: 900 },
            { fromMinutes: 2, toMinutes: 5, vehicles: 30, reports: 60 },
            { fromMinutes: 5, toMinutes: 10, vehicles: 10, reports: 30 },
            { fromMinutes: 10, toMinutes: null, vehicles: 2, reports: 10 },
          ],
        }),
      ),
      { range: '1h' },
    );
    expect([...el.querySelectorAll('table.bands tbody tr td:first-child')].map(text)).toEqual([
      'On time (under 2 min late, or early)',
      'More than 2 min (2–5)',
      'More than 5 min (5–10)',
      'More than 10 min',
    ]);
  });
});

describe('Position history charts', () => {
  const series = (from: string, to: string, times: string[]) => ({
    from,
    to,
    stepSeconds: 60,
    route: null,
    points: times.map((time) => ({
      time,
      vehicles: 100,
      peakVehicles: 110,
      samples: 500,
      meanDelaySeconds: 60,
    })),
    summary: {
      samples: 500,
      vehicles: 120,
      peakVehicles: 110,
      latestVehicles: 100,
      latestPoll: to,
      meanDelaySeconds: 60,
      first: from,
      last: to,
    },
  });

  it("span the selected window at once, leaving out the previous window's points outside it", () => {
    const old = series('2026-11-17T08:00:00Z', '2026-11-17T12:00:00Z', [
      '2026-11-17T09:00:00Z',
      '2026-11-17T10:00:00Z',
      '2026-11-17T11:00:00Z',
    ]);
    // A new window, 10:00–14:00, chosen; its series hasn't arrived yet.
    const query = {
      from: '2026-11-17T10:00:00.000Z',
      to: '2026-11-17T14:00:00.000Z',
      route: null,
      agency: 'BKM',
    };
    const { fixture } = renderPage(
      PositionHistory,
      stateAfter(
        positionHistoryFeature,
        PositionHistoryActions.load({
          query: {
            from: '2026-11-17T08:00:00.000Z',
            to: '2026-11-17T12:00:00.000Z',
            route: null,
            agency: 'BKM',
          },
        }),
        PositionHistoryActions.fleetLoaded({ series: old }),
        PositionHistoryActions.load({ query }),
      ),
      { from: '2026-11-17T10:00:00Z', to: '2026-11-17T14:00:00Z' },
    );
    const charts = (
      fixture.componentInstance as unknown as {
        charts: () => { from: number; to: number; vehicles: { time: number }[]; rows: unknown[] };
      }
    ).charts()!;

    expect(charts.from).toBe(Date.parse(query.from));
    expect(charts.to).toBe(Date.parse(query.to));
    expect(charts.vehicles.map((p) => new Date(p.time).toISOString())).toEqual([
      '2026-11-17T10:00:00.000Z',
      '2026-11-17T11:00:00.000Z',
    ]);
    expect(charts.rows).toHaveLength(2);
  });
});

describe('Position history controls', () => {
  it('put the time range, a custom window, the line and the operator in the URL', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 10, 16, 12, 0, 30));
    const { el, fixture } = renderPage(
      PositionHistory,
      stateAfter(
        positionHistoryFeature,
        PositionHistoryActions.agenciesLoaded({ agencies: ['BKM', 'TUROSN'] }),
      ),
      { range: '1h', route: '103' },
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const merge = (queryParams: Record<string, unknown>) => [
      [],
      { queryParams, queryParamsHandling: 'merge' },
    ];

    const presets = [
      ...el.querySelectorAll('app-time-window .presets button'),
    ] as HTMLButtonElement[];
    presets.find((b) => text(b) === '30 min')!.click();
    expect(navigate).toHaveBeenLastCalledWith(...merge({ range: '30m', from: null, to: null }));

    const [from] = [...el.querySelectorAll('input[type=datetime-local]')] as HTMLInputElement[];
    from.value = '2026-11-16T08:00';
    from.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ range: null, from: '2026-11-16T08:00', to: '2026-11-16T12:00' }),
    );

    const agency = el.querySelector('select.agency') as HTMLSelectElement;
    expect([...agency.options].map(text)).toEqual(['All operators', 'BKM', 'TUROSN']);
    expect(agency.value).toBe('BKM');
    agency.value = 'TUROSN';
    agency.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(...merge({ agency: 'TUROSN', route: null }));
    agency.value = 'all';
    agency.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(...merge({ agency: 'all', route: null }));

    const line = el.querySelectorAll('select')[1] as HTMLSelectElement;
    line.value = '';
    line.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(...merge({ route: null }));
  });
});

describe('Live map (Home)', () => {
  const position = (
    vehicleNumber: string,
    delaySeconds: number | null,
    agency = 'BKM',
    brigade: string | null = '103-01',
  ): LivePosition => ({
    vehicleId: 1,
    vehicleNumber,
    type: 'BUS',
    agency,
    model: null,
    routeName: '103',
    routeColor: null,
    brigade,
    headsign: 'Choroszcz',
    latitude: 53.13,
    longitude: 23.16,
    bearing: 90,
    delaySeconds,
    timestamp: '2026-11-16T12:00:00Z',
    upcomingStops: [
      {
        sequence: 4,
        name: 'Rynek',
        scheduledArrival: '2026-11-16T12:05:00Z',
        scheduledDeparture: null,
      },
    ],
    subtype: 'suburban',
  });
  const live = (...positions: LivePosition[]) =>
    stateAfter(
      livePositionsFeature,
      LivePositionsActions.loadSuccess({ positions, loadedAt: '2026-11-16T12:00:00Z' }),
    );
  const noSchedules = {
    provide: BrigadeApi,
    useValue: { schedule: () => of({ brigade: '', serviceDay: '', runs: [] }) },
  };

  it('polls the live positions and filters them by delay and operator from the URL', () => {
    vi.useFakeTimers();
    const { el, store, render } = renderPage(
      LiveMap,
      live(
        position('230', 600),
        position('009', 30),
        position('512', null),
        position('1051', 900, 'WSCHODEXPRESS'),
      ),
      // The recorded positions' filters (window, operator) are other parameters: they don't change the map.
      { mapMinDelay: '5', mapAgency: 'BKM', range: '1h', agency: 'WSCHODEXPRESS' },
      [noSchedules],
    );
    vi.advanceTimersByTime(0);
    expect(store.dispatch).toHaveBeenCalledWith(LivePositionsActions.load());
    render();
    expect(text(el.querySelector('.header .status'))).toContain(
      '1 of 4 vehicles (BKM, delayed over 5 min)',
    );
    expect([...el.querySelectorAll('select.agency option')].map(text)).toEqual([
      'All operators',
      'BKM',
      'WSCHODEXPRESS',
    ]);
  });

  it('puts the operator and the kind of line in the URL, and resets every filter', () => {
    const { el, fixture } = renderPage(LiveMap, live(position('230', 600)), { mapMinDelay: '5' }, [
      noSchedules,
    ]);
    const router = fixture.debugElement.injector.get(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const select = el.querySelector('select.agency') as HTMLSelectElement;
    select.value = 'BKM';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { mapAgency: 'BKM' },
      queryParamsHandling: 'merge',
    });

    const lines = el.querySelector('select.line-type') as HTMLSelectElement;
    expect([...lines.options].map((o) => o.textContent?.trim())).toEqual([
      'All lines',
      'Urban',
      'Suburban',
      'Regional (4xx)',
    ]);
    lines.value = 'suburban';
    lines.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { mapLineType: 'suburban' },
      queryParamsHandling: 'merge',
    });

    (el.querySelector('button.reset') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { mapMinDelay: null, mapAgency: null, mapLineType: null },
      queryParamsHandling: 'merge',
    });
  });

  it("colours vehicles by delay, with no outline but a shadow, and shows the rest of the brigade's day", async () => {
    useSvgMaps();
    const now = Date.now();
    const at = (minutes: number) => new Date(now + minutes * 60_000).toISOString();
    const run = (line: string, from: number, to: number) => ({
      line,
      headsign: 'Choroszcz',
      from: 'Piastowska',
      to: 'Choroszcz',
      departure: at(from),
      arrival: at(to),
    });
    const schedule = vi.fn(() =>
      of({
        brigade: '103-01',
        serviceDay: '2026-11-16',
        runs: [run('103', -90, -50), run('103', -10, 30), run('103', 40, 80)],
      }),
    );
    const { el, fixture } = renderPage(LiveMap, live(position('230', 600)), {}, [
      { provide: BrigadeApi, useValue: { schedule } },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    const marker = el.querySelector('path.leaflet-interactive') as SVGPathElement;
    expect(marker.getAttribute('fill')).toBe('#d7301f');
    expect(marker.getAttribute('stroke')).toBe('none');
    expect(marker.classList).toContain('live-vehicle');
    expect(text(el.querySelector('.legend'))).not.toContain('Suburban');

    // The city limits and centre are drawn under the vehicles, and don't take clicks.
    expect(el.querySelectorAll('.leaflet-city-boundaries-pane path')).toHaveLength(2);

    marker.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(schedule).toHaveBeenCalledWith('103-01', undefined);
    const popup = el.querySelector('.leaflet-popup-content')!;
    expect(text(popup.querySelector('.brigade > strong'))).toBe('Brigade 103-01');
    const runs = [...popup.querySelectorAll('ol.runs li')];
    expect(runs).toHaveLength(2);
    expect(runs[0].classList).toContain('now');
  });
});

describe('Routes page', () => {
  const lines = [
    {
      id: 1,
      line: '5',
      name: '5',
      subtype: 'urban' as const,
      geometry: 'arcantial' as const,
      capacityClass: 'main' as const,
      operatingAgency: 'KPKM',
    },
    {
      id: 2,
      line: '103',
      name: '103',
      subtype: 'suburban' as const,
      geometry: 'arcantial' as const,
      capacityClass: 'secondary' as const,
      operatingAgency: 'KZK',
    },
  ];

  it('loads the lines and the line in the URL, defaulting to the first line', () => {
    useSvgMaps();
    const withUrl = renderPage(
      RoutesPage,
      stateAfter(routeDetailFeature, RouteDetailActions.linesLoaded({ lines })),
      {
        line: '103',
      },
    );
    expect(withUrl.store.dispatch).toHaveBeenCalledWith(RouteDetailActions.loadLines());
    expect(withUrl.store.dispatch).toHaveBeenCalledWith(RouteDetailActions.select({ line: '103' }));
    // A row per kind of line, a column per geometry; the buttons show the capacity class.
    const table = withUrl.el.querySelector('table.lines')!;
    expect([...table.querySelectorAll('tbody th')].map(text)).toEqual(['Urban', 'Suburban']);
    expect([...table.querySelectorAll('thead th')].map(text)).toEqual([
      'Kind of line',
      'Radial',
      'Diameter',
      'Arcantial',
      'Tangential',
      'Feeder',
    ]);
    const five = [...table.querySelectorAll('.chip')].find((b) => text(b) === '5')!;
    expect(five.classList).toContain('capacity-main');
    expect(five.closest('td')).toBe(
      table.querySelectorAll('tbody tr')[0].querySelectorAll('td')[2],
    );
    expect(five.getAttribute('title')).toBe('Line 5: arcantial, main capacity');
    expect([...withUrl.el.querySelectorAll('.capacity-legend li')].map(text).slice(0, 5)).toEqual([
      '12 Main',
      '12 Primary',
      '12 Secondary',
      '12 Tertiary',
      '12 Irregular',
    ]);
    expect(text(withUrl.el.querySelector('.chip[aria-pressed=true]'))).toBe('103');
  });

  it('defaults to the first line, and shows the line with its sections once loaded', async () => {
    useSvgMaps();
    const detail = {
      id: 2,
      line: '103',
      name: '103',
      subtype: 'suburban',
      geometry: 'arcantial',
      capacityClass: 'secondary',
      serviceId: 'P_1349',
      url: 'https://komunikacja.bialystok.pl/#/route/103',
      base: {
        lengthKm: 19.8,
        shortestMin: 45,
        longestMin: 53,
        minSpeedKmh: 22.2,
        maxSpeedKmh: 26.7,
      },
      variants: [
        {
          direction: 0,
          shapeId: 's0',
          headsign: 'Choroszcz',
          kind: 'main',
          km: 20.1,
          tripsPerDay: 32,
          shortestMin: 45,
          longestMin: 50,
          minSpeedKmh: 24,
          maxSpeedKmh: 26.7,
          firstStop: 'Piastowska',
          lastStop: 'Choroszcz/Cmentarz',
          points: [
            [53.1, 23.1],
            [53.12, 23.0],
          ],
          stops: [{ name: 'Piastowska', lat: 53.1, lon: 23.1, km: 0, zone: '1' }],
        },
      ],
      zone2: [],
      brigades: [],
      endStops: [{ name: 'Piastowska', alsoNames: [] }],
      depotStops: [],
      depotRuns: [],
      operator: {
        organiser: 'BKM',
        code: 'KZK',
        name: 'Komunalny Zakład Komunikacyjny sp. z o.o.',
        website: 'https://www.kzk.pl',
        depot: 'Zajezdnia autobusowa KZK',
        depotAddress: 'ul. Pomocna 1',
      },
      depots: [
        {
          code: 'KZK',
          name: 'Zajezdnia autobusowa KZK',
          address: 'ul. Pomocna 1',
          outlines: [
            [
              [53.138, 23.196],
              [53.139, 23.197],
              [53.138, 23.198],
              [53.138, 23.196],
            ],
          ],
        },
        {
          code: 'KPKM',
          name: 'Zajezdnia autobusowa KPKM',
          address: 'ul. Składowa 11',
          outlines: [
            [
              [53.116, 23.129],
              [53.117, 23.13],
              [53.116, 23.131],
              [53.116, 23.129],
            ],
          ],
        },
      ],
      depotLinks: [
        {
          kind: 'pull_out',
          stop: 'Piastowska',
          lat: 53.1,
          lon: 23.1,
          depot: 'KZK',
          brigades: 2,
          path: [
            [53.138, 23.197],
            [53.12, 23.15],
            [53.1, 23.1],
          ],
          routed: true,
        },
      ],
      brigadesKnown: true,
      busLanes: [
        {
          direction: 0,
          from: 'Piastowska',
          to: 'Choroszcz',
          km: 12.5,
          laneKm: 1.9,
          percent: 15.2,
          onLanes: [],
        },
        {
          direction: 1,
          from: 'Choroszcz',
          to: 'Piastowska',
          km: 12.4,
          laneKm: 2.3,
          percent: 18.5,
          onLanes: [],
        },
      ],
    } as unknown as LineDetail;
    const performance = {
      firstDay: '2026-11-06',
      lastDay: '2026-11-16',
      weekdays: 3,
      fullRuns: 109,
      travelTimes: [],
      delays: [],
      segments: [],
      vehicles: { days: 7, vehicles: [], brigades: [], switches: [] },
    } as LinePerformance;
    // Every line's weekday statistics: the chosen line's (route 2) regularity goes in its tile.
    const lineStats = {
      serviceId: 'P_1349',
      validDays: 59,
      firstDate: '2026-11-02',
      lastDate: '2027-01-29',
      peak: [{ from: '06:30', to: '08:30' }],
      offPeak: [{ from: '08:30', to: '15:00' }],
      lines: [
        { routeId: 1, peakRegularityCv: 0.9, offPeakRegularityCv: 0.9 },
        { routeId: 2, peakRegularityCv: 0.18, offPeakRegularityCv: 0.45 },
      ],
    } as unknown as LineStatsReport;
    const { el, store, fixture } = renderPage(RoutesPage, {
      ...stateAfter(
        routeDetailFeature,
        RouteDetailActions.linesLoaded({ lines }),
        RouteDetailActions.select({ line: '103' }),
        RouteDetailActions.detailLoaded({ detail }),
        RouteDetailActions.performanceLoaded({ line: '103', performance }),
      ),
      ...stateAfter(lineStatsFeature, LineStatsActions.loaded({ report: lineStats })),
    });

    expect(store.dispatch).toHaveBeenCalledWith(LineStatsActions.load());
    expect(text(el.querySelector('.tile.regularity strong'))).toBe('A peak · D off-peak');
    expect(text(el.querySelector('.tile.regularity'))).toContain('Peak 06:30–08:30');
    expect(store.dispatch).toHaveBeenCalledWith(RouteDetailActions.select({ line: '5' }));
    expect(text(el.querySelector('.line-title h2'))).toBe('Piastowska ⇄ Choroszcz/Cmentarz');
    // Every depot's area, the operator's named; a dashed way from it to where brigades start.
    await fixture.whenStable();
    fixture.detectChanges();
    expect([...el.querySelectorAll('.leaflet-tooltip.depot-label')].map(text)).toEqual([
      'Zajezdnia autobusowa KZK, ul. Pomocna 1',
    ]);
    const dashed = [...el.querySelectorAll('.leaflet-map .leaflet-overlay-pane path')].filter(
      (p) => p.getAttribute('stroke-dasharray') === '8 6',
    );
    expect(dashed).toHaveLength(1);
    expect(text(el.querySelector('.map-legend'))).toContain(
      'Depot to where buses start or end their day (dashed)',
    );
    expect(text(el.querySelector('.map-legend'))).toContain('City centre (for line classes)');
    // City limits and the line-classification centre, under the line.
    expect(el.querySelectorAll('.leaflet-city-boundaries-pane path').length).toBeGreaterThan(0);
    // Who runs it.
    expect(text(el.querySelector('.line-title .operator'))).toContain(
      'Organised by BKM; operated by KZK (Komunalny Zakład Komunikacyjny sp. z o.o.), from Zajezdnia autobusowa KZK, ' +
        'ul. Pomocna 1. KZK website ↗',
    );
    // How much of it runs on bus lanes, drawn on the map too.
    expect(text(el.querySelector('.line-title .bus-lanes'))).toBe(
      'On bus lanes: to Choroszcz 1.9 of 12.5 km (15%); to Piastowska 2.3 of 12.4 km (19%) Bus lanes ↗',
    );
    expect(el.querySelectorAll('.leaflet-bus-lanes-pane path')).toHaveLength(2);
    expect(text(el.querySelector('.map-legend'))).toContain('Bus lane');
    expect(text(el.querySelector('.line-title .muted'))).toBe('Suburban line');
    expect([...el.querySelectorAll('.line-classes dt')].map(text)).toEqual([
      'Arcantial',
      'Secondary capacity',
    ]);
    expect(text(el.querySelector('.line-classes dd'))).toContain('Two radial legs at an angle');
    expect(el.querySelectorAll('app-variant-tiles .tile')).toHaveLength(6);
    expect(text(el.querySelector('app-travel-charts'))).toContain('109 full runs');
    const links = [...el.querySelectorAll('.line-links a')] as HTMLAnchorElement[];
    expect(links.map(text)).toEqual(['Trips', 'Timetable on the website ↗']);
    expect(links[0].getAttribute('href')).toBe('/trips?routeId=2');
    expect(links[1].href).toBe('https://komunikacja.bialystok.pl/#/route/103');
  });

  it("adds a row of regional lines, and shows one that isn't in the timetable from recordings", () => {
    useSvgMaps();
    const regional = [
      ...lines,
      {
        id: null,
        line: '700',
        name: '700',
        subtype: 'regional' as const,
        geometry: null,
        capacityClass: null,
        operatingAgency: null,
      },
    ];
    const detail = {
      id: null,
      line: '700',
      name: '700',
      subtype: 'regional',
      serviceId: null,
      url: null,
      base: null,
      variants: [],
      zone2: [],
      brigades: [],
      endStops: [],
      depotStops: [],
      depotRuns: [],
      operator: null,
      depots: [],
      depotLinks: [],
      busLanes: [],
      recorded: {
        first: '2026-11-01T06:00:00Z',
        last: '2026-11-16T18:00:00Z',
        runs: 100,
        variants: [
          {
            id: 1,
            headsign: 'Turośń Dolna',
            from: 'Dworzec PKS',
            to: 'Turośń Dolna W2',
            runs: 60,
            km: 26.4,
            lastSeen: '2026-11-16T18:00:00Z',
            path: [
              [53.13, 23.14],
              [53.05, 23.25],
            ],
          },
          {
            id: 2,
            headsign: 'Turośń Dolna',
            from: 'Turośń Dolna Nr 7',
            to: 'Piećki',
            runs: 2,
            km: 5.3,
            lastSeen: '2026-11-10T08:00:00Z',
            path: [
              [53.05, 23.25],
              [53.03, 23.27],
            ],
          },
        ],
      },
    } as unknown as LineDetail;
    const performance = {
      firstDay: '2026-11-06',
      lastDay: '2026-11-16',
      weekdays: 9,
      fullRuns: 0,
      travelTimes: [],
      delays: [{ minute: 420, avgDelaySeconds: 60, samples: 30 }],
      segments: [],
      vehicles: {
        days: 9,
        vehicles: [
          {
            number: 'BIA 1050',
            model: null,
            days: 9,
            brigades: ['700-01'],
            lastSeen: '2026-11-16T10:00:00Z',
          },
        ],
        brigades: [],
        switches: [],
      },
    } as unknown as LinePerformance;
    const { el, fixture } = renderPage(
      RoutesPage,
      stateAfter(
        routeDetailFeature,
        RouteDetailActions.linesLoaded({ lines: regional }),
        RouteDetailActions.select({ line: '700' }),
        RouteDetailActions.detailLoaded({ detail }),
        RouteDetailActions.performanceLoaded({ line: '700', performance }),
      ),
      { line: '700' },
    );

    expect([...el.querySelectorAll('table.lines tbody th')].map(text)).toEqual([
      'Urban',
      'Suburban',
      'Regional',
    ]);
    // Other operators' lines aren't classified: a column of their own.
    expect(text(el.querySelector('table.lines thead th:last-child'))).toBe('Not classified');
    expect(el.querySelector('.chip[aria-pressed=true]')!.classList).toContain('capacity-none');
    expect(el.querySelector('.line-classes')).toBeNull();
    expect(text(el.querySelector('.chip[aria-pressed=true]'))).toBe('700');
    expect(text(el.querySelector('.line-title h2'))).toBe('Line 700');
    expect(el.querySelectorAll('.line-links a')).toHaveLength(0);
    expect(text(el)).toContain("isn't in the city timetable: it is run by another operator");
    expect(el.querySelector('app-variant-tiles')).toBeNull();
    expect(text(el.querySelector('app-line-vehicles'))).toContain('BIA 1050');
    // Where its buses went, from the recordings: on the map and listed, the most driven first.
    expect((el.querySelector('.map-card') as HTMLElement).hidden).toBe(false);
    const rows = [...el.querySelectorAll('table.recorded-variants tbody tr')];
    expect(rows.map((r) => text(r.querySelector('td:nth-child(3)')))).toEqual([
      '60 times (60%)',
      '2 times (2%)',
    ]);
    expect(text(rows[0].querySelector('td:nth-child(2)'))).toBe('Dworzec PKS → Turośń Dolna W2');
    expect(text(el.querySelector('.map-legend'))).toContain('thin and dashed: driven rarely');
    const paths = () => [...el.querySelectorAll('.leaflet-map .leaflet-overlay-pane path')];
    expect(paths().filter((p) => p.getAttribute('stroke-dasharray') === '6 5')).toHaveLength(1);
    // Choosing one draws it thick and fades the rest.
    (rows[1] as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('table.recorded-variants tr.selected')).toBe(rows[1]);
    expect(paths().some((p) => p.getAttribute('stroke-width') === '7')).toBe(true);
  });
});
