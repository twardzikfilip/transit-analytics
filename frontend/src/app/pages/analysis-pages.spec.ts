import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { BrigadeApi } from '../core/api/api.services';
import {
  CorridorReport,
  LineDetail,
  LinePerformance,
  LineStats,
  LivePosition,
} from '../core/models/models';
import { CorridorsActions } from '../store/corridors/corridors.actions';
import { corridorsFeature } from '../store/corridors/corridors.reducer';
import { DelayReplayActions } from '../store/delay-replay/delay-replay.actions';
import { delayReplayFeature } from '../store/delay-replay/delay-replay.reducer';
import { LineStatsActions } from '../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../store/line-stats/line-stats.reducer';
import { LivePositionsActions } from '../store/live-positions/live-positions.actions';
import { livePositionsFeature } from '../store/live-positions/live-positions.reducer';
import { PositionHistoryActions } from '../store/position-history/position-history.actions';
import { positionHistoryFeature } from '../store/position-history/position-history.reducer';
import { RouteDetailActions } from '../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../store/route-detail/route-detail.reducer';
import { renderPage, stateAfter, useSvgMaps, text } from '../testing/page-testing';
import { Corridors } from './corridors/corridors';
import { Delays } from './delays/delays';
import { LineStatsPage } from './line-stats/line-stats';
import { LiveMap } from './live-map/live-map';
import { Positions } from './positions/positions';
import { RouteDetail } from './route-detail/route-detail';

afterEach(() => {
  // Destroy the pages (and their maps) first: Leaflet keeps redrawing on animation frames until a map is removed.
  TestBed.resetTestingModule();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const line = (route: string, lengthKm: number, peakSpeedKmh: number): LineStats => ({
  routeId: Number(route),
  route,
  color: null,
  textColor: null,
  lengthKm,
  shortestTravelSeconds: 1800,
  longestTravelSeconds: 2400,
  peakSpeedKmh,
  offPeakSpeedKmh: 20,
  peakIntervalMinutes: 10,
  offPeakIntervalMinutes: 15,
  peakRegularityCv: 0.2,
  offPeakRegularityCv: 0.3,
  peakVehicles: 5,
  offPeakVehicles: 4,
  trips: 60,
});

describe('Line statistics page', () => {
  it('loads the report, states the periods and ranks lines in the top 5 tables', () => {
    const report = {
      serviceId: 'P_1349',
      validDays: 59,
      firstDate: '2026-11-02',
      lastDate: '2027-01-29',
      peak: [
        { from: '06:30', to: '08:30' },
        { from: '15:00', to: '17:00' },
      ],
      offPeak: [
        { from: '08:30', to: '15:00' },
        { from: '17:00', to: '20:00' },
      ],
      lines: [line('5', 8.6, 18), line('103', 19.8, 25), line('10', 9.9, 16)],
    };
    const { el, store } = renderPage(
      LineStatsPage,
      stateAfter(lineStatsFeature, LineStatsActions.loaded({ report })),
    );

    expect(store.dispatch).toHaveBeenCalledWith(LineStatsActions.load());
    expect(text(el.querySelector('.intro'))).toContain('Peak hours: 06:30–08:30 and 15:00–17:00');
    expect(text(el.querySelector('.intro'))).toContain('Off-peak: 08:30–15:00 and 17:00–20:00');
    const longest = [...el.querySelectorAll('.top')].find(
      (t) => text(t.querySelector('h2')) === 'Top 5 longest lines',
    )!;
    expect(
      [...longest.querySelectorAll('tbody tr')].map((r) => text(r.querySelector('td'))),
    ).toEqual(['103', '10', '5']);
  });

  it('says when no timetable is imported', () => {
    const report = {
      serviceId: null,
      validDays: 0,
      firstDate: null,
      lastDate: null,
      peak: [],
      offPeak: [],
      lines: [],
    };
    const { el } = renderPage(
      LineStatsPage,
      stateAfter(lineStatsFeature, LineStatsActions.loaded({ report })),
    );
    expect(text(el)).toContain('No timetable yet');
  });
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

describe('Position history page', () => {
  it('loads the window in the URL, with its line, report page, sort and outlier choice', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store } = renderPage(Positions, stateAfter(positionHistoryFeature), {
      range: '1h',
      route: '103',
      page: '3',
      sort: 'delay,desc',
      hideOutliers: '1',
    });

    const query = {
      from: '2026-11-16T11:00:40.000Z',
      to: '2026-11-16T12:00:40.000Z',
      route: '103',
    };
    expect(store.dispatch).toHaveBeenCalledWith(PositionHistoryActions.load({ query }));
    expect(store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.loadSamples({
        query,
        page: 2,
        sort: 'delay,desc',
        excludeOutliers: true,
      }),
    );
  });

  it('refreshes a short preset window each poll', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store } = renderPage(Positions, stateAfter(positionHistoryFeature), { range: '5m' });
    expect(store.dispatch).toHaveBeenCalledWith(
      PositionHistoryActions.load({
        query: { from: '2026-11-16T11:55:40.000Z', to: '2026-11-16T12:00:40.000Z', route: null },
      }),
    );
    const before = vi.mocked(store.dispatch).mock.calls.length;
    vi.advanceTimersByTime(20_000);
    expect(vi.mocked(store.dispatch).mock.calls.length).toBe(before + 2);
  });

  it('refreshes a preset window every minute, but not a custom one', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const preset = renderPage(Positions, stateAfter(positionHistoryFeature), { range: '6h' });
    const before = vi.mocked(preset.store.dispatch).mock.calls.length;
    vi.advanceTimersByTime(60_000);
    expect(vi.mocked(preset.store.dispatch).mock.calls.length).toBe(before + 2);
  });
});

describe('Position history controls', () => {
  it('put the time range, a custom window and the line in the URL, back on the first page', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 10, 16, 12, 0, 30));
    const { el, fixture } = renderPage(Positions, stateAfter(positionHistoryFeature), {
      range: '1h',
      page: '3',
    });
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const merge = (queryParams: Record<string, unknown>) => [
      [],
      { queryParams, queryParamsHandling: 'merge' },
    ];

    const presets = [...el.querySelectorAll('.buttons button')] as HTMLButtonElement[];
    presets.find((b) => text(b) === '15 min')!.click();
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ range: '15m', from: null, to: null, page: null }),
    );

    const [from] = [...el.querySelectorAll('input[type=datetime-local]')] as HTMLInputElement[];
    from.value = '2026-11-16T08:00';
    from.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ range: null, from: '2026-11-16T08:00', to: '2026-11-16T12:00', page: null }),
    );

    const line = el.querySelector('select') as HTMLSelectElement;
    line.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(...merge({ route: null, page: null }));
  });
});

describe('Delays page', () => {
  it('loads the replay for the window and lines in the URL', () => {
    useSvgMaps();
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-16T12:00:30Z'));
    const { store, el } = renderPage(Delays, stateAfter(delayReplayFeature), {
      range: '15m',
      lines: '103, 5',
    });

    expect(store.dispatch).toHaveBeenCalledWith(
      DelayReplayActions.load({
        query: {
          from: '2026-11-16T11:46:00.000Z',
          to: '2026-11-16T12:01:00.000Z',
          routes: ['103', '5'],
        },
      }),
    );
    const active = el.querySelector('.range button.active');
    expect(text(active)).toBe('Last 15 min');
  });

  it('shows a failure from the store', () => {
    useSvgMaps();
    const { el } = renderPage(
      Delays,
      stateAfter(
        delayReplayFeature,
        DelayReplayActions.load({ query: { from: 'a', to: 'b', routes: [] } }),
        DelayReplayActions.failure({ error: 'Too long a window' }),
      ),
    );
    expect(text(el)).toContain('Too long a window');
  });
});

describe('Delays player', () => {
  it('plays a loaded replay, pauses, and scrubs to a moment', async () => {
    useSvgMaps();
    const replay = {
      from: '2026-11-16T11:00:00Z',
      to: '2026-11-16T11:02:00Z',
      stepSeconds: 60,
      routes: ['103'],
      vehicles: ['230', '009'],
      lines: ['103'],
      frames: [
        {
          time: '2026-11-16T11:00:00Z',
          positions: [[0, 53.13, 23.16, 120, 0]] as [
            number,
            number,
            number,
            number | null,
            number | null,
          ][],
        },
        {
          time: '2026-11-16T11:01:00Z',
          positions: [
            [0, 53.131, 23.161, 180, 0],
            [1, 53.12, 23.15, -60, null],
          ] as [number, number, number, number | null, number | null][],
        },
      ],
    };
    const { el, render, fixture } = renderPage(
      Delays,
      stateAfter(
        delayReplayFeature,
        DelayReplayActions.load({ query: { from: replay.from, to: replay.to, routes: [] } }),
        DelayReplayActions.replayLoaded({ replay }),
      ),
    );
    await fixture.whenStable();
    render();
    const play = el.querySelector('button.play') as HTMLButtonElement;
    expect(text(play)).toBe('❚❚ Pause');

    play.click();
    render();
    expect(text(play)).toBe('▶ Play');

    const timeline = el.querySelector('input.timeline') as HTMLInputElement;
    timeline.value = String(Date.parse('2026-11-16T11:01:00Z'));
    timeline.dispatchEvent(new Event('input'));
    render();
    expect(text(el.querySelector('.time .muted'))).toBe('· 2 vehicles');
  });
});

describe('Live map page', () => {
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
      { minDelay: '5', agency: 'BKM' },
      [noSchedules],
    );
    vi.advanceTimersByTime(0);
    expect(store.dispatch).toHaveBeenCalledWith(LivePositionsActions.load());
    render();
    expect(text(el.querySelector('.header .muted'))).toContain(
      '1 of 4 vehicles (BKM, delayed over 5 min)',
    );
    expect([...el.querySelectorAll('select.agency option')].map(text)).toEqual([
      'All operators',
      'BKM',
      'WSCHODEXPRESS',
    ]);
  });

  it('puts the operator in the URL, and resets every filter', () => {
    const { el, fixture } = renderPage(LiveMap, live(position('230', 600)), { minDelay: '5' }, [
      noSchedules,
    ]);
    const router = fixture.debugElement.injector.get(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const select = el.querySelector('select.agency') as HTMLSelectElement;
    select.value = 'BKM';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { agency: 'BKM' },
      queryParamsHandling: 'merge',
    });

    (el.querySelector('button.reset') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { minDelay: null, agency: null },
      queryParamsHandling: 'merge',
    });
  });

  it("colours vehicles by delay, outlined by kind of line, and shows the rest of the brigade's day", async () => {
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
    expect(marker.getAttribute('stroke')).toBe('#111111');

    marker.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(schedule).toHaveBeenCalledWith('103-01');
    const popup = el.querySelector('.leaflet-popup-content')!;
    expect(text(popup.querySelector('.brigade > strong'))).toBe('Brigade 103-01');
    const runs = [...popup.querySelectorAll('ol.runs li')];
    expect(runs).toHaveLength(2);
    expect(runs[0].classList).toContain('now');
  });
});

describe('Routes in detail page', () => {
  const lines = [
    { id: 1, line: '5', name: '5', subtype: 'urban' as const },
    { id: 2, line: '103', name: '103', subtype: 'suburban' as const },
  ];

  it('loads the lines and the line in the URL, defaulting to the first line', () => {
    useSvgMaps();
    const withUrl = renderPage(
      RouteDetail,
      stateAfter(routeDetailFeature, RouteDetailActions.linesLoaded({ lines })),
      {
        line: '103',
      },
    );
    expect(withUrl.store.dispatch).toHaveBeenCalledWith(RouteDetailActions.loadLines());
    expect(withUrl.store.dispatch).toHaveBeenCalledWith(RouteDetailActions.select({ line: '103' }));
    expect([...withUrl.el.querySelectorAll('.group-label')].map(text)).toEqual([
      'Urban',
      'Suburban',
    ]);
    expect(text(withUrl.el.querySelector('.chip[aria-pressed=true]'))).toBe('103');
  });

  it('defaults to the first line, and shows the line with its sections once loaded', () => {
    useSvgMaps();
    const detail = {
      id: 2,
      line: '103',
      name: '103',
      subtype: 'suburban',
      serviceId: 'P_1349',
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
    const { el, store } = renderPage(
      RouteDetail,
      stateAfter(
        routeDetailFeature,
        RouteDetailActions.linesLoaded({ lines }),
        RouteDetailActions.select({ line: '103' }),
        RouteDetailActions.detailLoaded({ detail }),
        RouteDetailActions.performanceLoaded({ line: '103', performance }),
      ),
    );

    expect(store.dispatch).toHaveBeenCalledWith(RouteDetailActions.select({ line: '5' }));
    expect(text(el.querySelector('.line-title h2'))).toBe('Piastowska ⇄ Choroszcz/Cmentarz');
    expect(text(el.querySelector('.line-title .muted'))).toBe('Suburban line');
    expect(el.querySelectorAll('app-variant-tiles .tile')).toHaveLength(5);
    expect(text(el.querySelector('app-travel-charts'))).toContain('109 full runs');
  });
});
