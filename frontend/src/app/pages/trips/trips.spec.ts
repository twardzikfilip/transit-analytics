import { TripRun, TripRunPage } from '../../core/models/models';
import { TripRunsActions } from '../../store/trip-runs/trip-runs.actions';
import { tripRunsFeature } from '../../store/trip-runs/trip-runs.reducer';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { Trips, readQuery, refreshes, tripKey } from './trips';
import { convertToParamMap } from '@angular/router';
import { readWindow } from '../../shared/time-window/time-window';
import { localTime } from '../../shared/format/time';
import { of, throwError } from 'rxjs';
import { TripRunApi } from '../../core/api/api.services';
import { useSvgMaps } from '../../testing/page-testing';
import { delayPath, stopTooltip } from './trip-map';
import { DELAY_STOPS } from '../../shared/palette';

const at = (hhmm: string) => `2026-11-23T${hhmm}:00Z`;

/** Line 103 at 14:00–14:30 run by bus 230, changed to 231 at 14:15, standing 4 min on the way. */
function run(overrides: Partial<TripRun['trip']> = {}): TripRun {
  return {
    serviceDay: '2026-11-23',
    brigade: '103-05',
    trip: {
      tripId: 't1',
      line: '103',
      headsign: 'Choroszcz',
      from: 'Piastowska',
      to: 'Choroszcz/Cmentarz',
      direction: 0,
      departure: at('14:00'),
      arrival: at('14:30'),
      stops: 20,
      kind: 'vehicle-changed',
      note: null,
      runs: [
        {
          bus: '230',
          fromStop: 1,
          toStop: 10,
          fromName: 'Piastowska',
          toName: 'Rynek',
          departure: at('14:02'),
          arrival: at('14:15'),
          startDelay: 120,
          endDelay: 300,
        },
        {
          bus: '231',
          fromStop: 10,
          toStop: 20,
          fromName: 'Rynek',
          toName: 'Choroszcz/Cmentarz',
          departure: at('14:17'),
          arrival: at('14:34'),
          startDelay: 420,
          endDelay: 240,
        },
      ],
      missing: [],
      ...overrides,
    },
    samples: [
      { at: at('14:02'), bus: '230', delay: 120, kmh: null, stop: 2 },
      { at: at('14:03'), bus: '230', delay: 300, kmh: 0, stop: 6 },
      { at: at('14:20'), bus: '231', delay: 420, kmh: 30, stop: 12 },
    ],
    stands: [
      {
        bus: '230',
        from: at('14:08'),
        to: at('14:12'),
        minutes: 4,
        stop: 'Lipowa',
        stopIndex: 6,
        delayGrowth: 180,
        lat: 53.13,
        lon: 23.16,
      },
    ],
    stops: [
      { index: 1, name: 'Piastowska', scheduled: at('14:00'), passed: at('14:02'), delay: 120 },
      { index: 2, name: 'Lipowa', scheduled: at('14:10'), passed: at('14:13'), delay: 180 },
      { index: 3, name: 'Choroszcz/Cmentarz', scheduled: at('14:30'), passed: null, delay: null },
    ],
    averageKmh: 19,
  };
}

function page(trips: TripRun[], extra: Partial<TripRunPage> = {}): TripRunPage {
  return {
    from: at('13:30'),
    to: at('14:30'),
    shownFrom: at('13:30'),
    q: null,
    total: trips.length,
    page: 0,
    size: 50,
    trips,
    ...extra,
  };
}

describe('Trips page', () => {
  it('reads the last hour by default, the search and the page from the URL', () => {
    const now = new Date('2026-11-23T14:30:05Z');
    const q = readQuery(convertToParamMap({ q: ' 230 ', page: '3' }), now);
    expect(q.q).toBe('230');
    expect(q.page).toBe(2);
    expect(Date.parse(q.window.to) - Date.parse(q.window.from!)).toBe(3_600_000);
    expect(refreshes(readWindow(convertToParamMap({}), '1h', now))).toBe(true);
    expect(refreshes(readWindow(convertToParamMap({ range: '1w' }), '1h', now))).toBe(false);
    expect(readQuery(convertToParamMap({}), now).q).toBeNull();
  });

  it('loads the trips of the window and shows each with its time line', () => {
    const { el, store } = renderPage(
      Trips,
      stateAfter(tripRunsFeature, TripRunsActions.loaded({ page: page([run()]) })),
      { q: '103-05' },
    );
    expect(store.dispatch).toHaveBeenCalledWith(
      TripRunsActions.load({
        query: expect.objectContaining({ q: '103-05', page: 0 }) as never,
      }),
    );
    const row = el.querySelector('.trip button.row')!;
    expect(text(row.querySelector('.time'))).toBe(
      `${localTime(at('14:00'))}–${localTime(at('14:30'))}`,
    );
    expect(text(row.querySelector('.route-badge'))).toBe('103');
    expect(text(row.querySelector('.to'))).toBe('→ Choroszcz');
    expect(text(row.querySelector('.second'))).toContain('Brigade 103-05 · bus 230 → 231');
    expect(row.querySelector('.second .tag.danger')).not.toBeNull();
    expect(row.querySelectorAll('app-trip-lane rect.stand')).toHaveLength(1);
    expect(row.querySelectorAll('app-trip-lane .marker')).toHaveLength(1);
    expect(el.querySelector('app-trip-detail')).toBeNull();
    expect(text(el.querySelector('app-pager'))).toContain('1 trip');
  });

  it('opens a trip in the URL with its delay, speed and what happened', () => {
    const r = run();
    const { el, fixture } = renderPage(
      Trips,
      stateAfter(tripRunsFeature, TripRunsActions.loaded({ page: page([r]) })),
      { trip: tripKey(r) },
    );
    expect(tripKey(r)).toBe('t1@2026-11-23');
    const detail = el.querySelector('.detail')!;
    expect(detail.querySelectorAll('app-trip-detail polyline.delay-line')).toHaveLength(2);
    expect(detail.querySelectorAll('app-trip-detail polyline.speed-line')).toHaveLength(2);
    expect(detail.querySelectorAll('app-trip-detail rect.stand')).toHaveLength(1);
    const events = [...detail.querySelectorAll('.events li')].map(text);
    expect(events[0]).toContain('bus 230 stood still 4 min heading to Lipowa (delay +3:00)');
    expect(events[1]).toContain('bus 230 replaced by 231 at Rynek');
    expect(text(detail.querySelector('.facts p'))).toBe(
      'Set off +2:00, last seen +4:00, at most +7:00.',
    );
    expect(text(detail.querySelector('.facts .stat'))).toBe(
      'Average speed 19 km/h over the whole trip, stops and waits included',
    );

    // Each stop: a tick on the scheduled bar when due, and a dot on the delay and speed lines where the bus passed
    // it, where they are drawn (only the delay line at Piastowska); hovering one tells its name and times.
    const chart = detail.querySelector('app-trip-detail')!;
    expect(chart.querySelectorAll('.stop-tick')).toHaveLength(3);
    expect(chart.querySelectorAll('.stop-point')).toHaveLength(1);
    expect(chart.querySelectorAll('.stop-point .dot')).toHaveLength(1);
    chart.querySelector('.stop-point')!.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(text(chart.querySelector('.tooltip'))).toContain('Piastowska');
    chart.querySelectorAll('.stop-hit')[1].dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    const tip = text(chart.querySelector('.tooltip'));
    expect(tip).toContain('Lipowa');
    expect(tip).toContain('Stop 2 of 20');
    expect(tip).toContain(`Due ${localTime(at('14:10'))}`);
    expect(tip).toContain(`Passed ${localTime(at('14:13'))} (+3:00)`);
    expect(chart.querySelector('.stop-guide')).not.toBeNull();
    chart.querySelectorAll('.stop-hit')[2].dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(text(chart.querySelector('.tooltip'))).toContain('Not seen passing');
    expect(detail.querySelector('a')!.getAttribute('href')).toBe(
      '/brigades/103-05?date=2026-11-23',
    );
  });

  it('says when nothing matches the search, and when a long window is cut to its last day', () => {
    const { el } = renderPage(
      Trips,
      stateAfter(
        tripRunsFeature,
        TripRunsActions.loaded({
          page: page([], { q: 'xyz', from: '2026-11-16T14:30:00Z', shownFrom: at('14:30') }),
        }),
      ),
    );
    expect(text(el.querySelector('.empty'))).toBe('No trip in the window matches “xyz”.');
    expect(text(el.querySelector('.note'))).toContain('last 24 hours');
  });

  it('loads an open trip in detail: speed from every report, how long it stood at stops, and its map', () => {
    useSvgMaps();
    const r = run();
    const detail = vi.fn(() =>
      of({
        tripId: 't1',
        route: [
          [53.1, 23.1],
          [53.2, 23.2],
        ] as [number, number][],
        stops: [
          { index: 1, name: 'Piastowska', lat: 53.1, lon: 23.1, dwellSeconds: 25 },
          { index: 2, name: 'Lipowa', lat: 53.12, lon: 23.12, dwellSeconds: 80 },
          { index: 3, name: 'Choroszcz/Cmentarz', lat: 53.14, lon: 23.14, dwellSeconds: null },
        ],
        points: [
          { at: at('14:02'), bus: '230', lat: 53.1, lon: 23.1, delay: 30, kmh: null },
          { at: at('14:03'), bus: '230', lat: 53.12, lon: 23.12, delay: 700, kmh: 0 },
          { at: at('14:04'), bus: '230', lat: 53.14, lon: 23.14, delay: 700, kmh: 24 },
        ],
      }),
    );
    const { el, setQuery, fixture } = renderPage(
      Trips,
      stateAfter(tripRunsFeature, TripRunsActions.loaded({ page: page([r]) })),
      { trip: tripKey(r) },
      [{ provide: TripRunApi, useValue: { detail } }],
    );
    expect(detail).toHaveBeenCalledWith(r);
    // The speed line follows every report: one piece, 14:03–14:04.
    const chart = el.querySelector('app-trip-detail')!;
    expect(chart.querySelectorAll('polyline.speed-line')).toHaveLength(1);
    expect(text(el.querySelector('.facts .halts'))).toBe(
      'Longest halts at stops: Lipowa 1:20 · Piastowska 0:25',
    );
    chart.querySelectorAll('.stop-hit')[1].dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(text(chart.querySelector('.tooltip'))).toContain('Stood 1:20 at the stop');

    const button = el.querySelector<HTMLButtonElement>('.detail .map-button')!;
    expect(text(button)).toBe('Map');
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(el.querySelector('app-trip-map')).toBeNull();
    setQuery({ trip: tripKey(r), map: '1' });
    expect(el.querySelector('.detail .map-button')!.getAttribute('aria-pressed')).toBe('true');
    expect(el.querySelector('app-trip-map .map')).not.toBeNull();
    expect(detail).toHaveBeenCalledTimes(1);
  });

  it("shows no map placeholder once the trip's detail has failed (the failure is a toast)", () => {
    useSvgMaps();
    const r = run();
    const { el } = renderPage(
      Trips,
      stateAfter(tripRunsFeature, TripRunsActions.loaded({ page: page([r]) })),
      { trip: tripKey(r), map: '1' },
      [{ provide: TripRunApi, useValue: { detail: () => throwError(() => new Error('500')) } }],
    );
    expect(el.querySelector('app-trip-map')).not.toBeNull();
    expect(el.querySelector('app-trip-map app-skeleton')).toBeNull();
  });

  it('colours the path between reports by the delay at the first, broken where the bus changes or vanished', () => {
    const p = (at: string, bus: string, delay: number | null) => ({
      at,
      bus,
      lat: 53.1,
      lon: 23.1,
      delay,
      kmh: null,
    });
    const segments = delayPath({
      tripId: 't',
      route: [],
      stops: [],
      points: [
        p('2026-11-23T14:00:00Z', '1', 0),
        p('2026-11-23T14:00:10Z', '1', 700),
        p('2026-11-23T14:00:20Z', '1', null),
        p('2026-11-23T14:05:00Z', '1', 0),
        p('2026-11-23T14:05:10Z', '2', 0),
      ],
    });
    expect(segments.map((s) => s.color)).toEqual([DELAY_STOPS[0].color, DELAY_STOPS[3].color]);
    expect(segments[1].tip).toContain('+11:40');
    expect(
      stopTooltip(
        { index: 2, name: 'Lipowa <1>' },
        { index: 2, name: 'Lipowa <1>', scheduled: at('14:10'), passed: null, delay: null },
        9,
      ),
    ).toBe(
      `<strong>Lipowa &#60;1&#62;</strong><br>Stop 2 of 9<br>Due ${localTime(at('14:10'))}<br>Not seen passing`,
    );
  });
});
