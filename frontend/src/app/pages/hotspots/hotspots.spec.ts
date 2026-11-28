import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { HotspotApi } from '../../core/api/api.services';
import { Hotspot, HotspotReport } from '../../core/models/models';
import { HotspotsActions } from '../../store/hotspots/hotspots.actions';
import { load } from '../../store/hotspots/hotspots.effects';
import { hotspotsFeature } from '../../store/hotspots/hotspots.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { approachText, hourText, routesText, lostTime, nearText, waitText } from './hotspot-format';
import { MAX_RADIUS, radiusFor } from './hotspot-map';
import { FIRST_ROWS, Hotspots } from './hotspots';

const hotspot = (id: number, hours: number): Hotspot => ({
  id,
  lat: 53.13 + id * 0.001,
  lon: 23.15,
  radiusMeters: 18,
  hours,
  minutesPerDay: hours * 4,
  standstills: 200,
  vehicles: 60,
  meanSeconds: 45,
  medianSeconds: 39,
  p90Seconds: 75,
  nearestStop: id === 1 ? 'Częstochowska/Piłsudskiego' : `Stop ${id}`,
  nearestStopMeters: 70,
  routes: [
    { route: '21', hours: 0.8 },
    { route: '9', hours: 0.5 },
  ],
  approaches: [
    { heading: 'E', share: 0.81 },
    { heading: 'SE', share: 0.19 },
  ],
  peakHour: 7,
});

function report(count = 2): HotspotReport {
  return {
    from: '2026-11-10T13:00:00Z',
    to: '2026-11-17T13:00:00Z',
    route: null,
    routes: ['9', '21'],
    days: 7,
    standstills: 4574,
    standstillHours: 47.9,
    hotspotHours: 44.1,
    hotspots: Array.from({ length: count }, (_, i) => hotspot(i + 1, 3 - i * 0.05)),
  };
}

afterEach(() => {
  // Destroy the page (and its map) first: Leaflet keeps redrawing on animation frames until a map is removed.
  TestBed.resetTestingModule();
});

const CUSTOM = {
  from: new Date('2026-11-01T06:00').toISOString(),
  to: new Date('2026-11-17T06:00').toISOString(),
};

describe('Stop hotspots page', () => {
  it('loads the window and line in the URL and lists the hotspots, the most time lost first', () => {
    useSvgMaps();
    const { el, store } = renderPage(
      Hotspots,
      stateAfter(hotspotsFeature, HotspotsActions.loaded({ report: report() })),
      { from: '2026-11-01T06:00', to: '2026-11-17T06:00', route: '21' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(
      HotspotsActions.load({ window: CUSTOM, route: '21' }),
    );
    expect(text(el.querySelector('.summary'))).toBe(
      'Buses stood still away from stops 4574 times, for 47.9 h (6.8 h a day over 7 days recorded). ' +
        '2 hotspots hold 92% of that time.',
    );
    const select = el.querySelector(
      'app-time-window select:not(.window-preset)',
    ) as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toEqual(['All lines', 'Line 9', 'Line 21']);
    expect(select.value).toBe('21');
    const cells = [...el.querySelectorAll('table.hotspots tbody tr:first-child td')].map(text);
    expect(cells).toEqual([
      '1',
      'Częstochowska/Piłsudskiego 70 m away',
      '3.0 h',
      '12.0 min',
      '200',
      '60',
      '39 s / 1:15 min',
      '→ 81%, ↘ 19%',
      '07–08',
      '21, 9',
    ]);
    expect(el.querySelector('app-hotspot-map')).not.toBeNull();
    expect(el.querySelectorAll('.leaflet-interactive')).toHaveLength(2);
  });

  it('chooses a hotspot in the table or on the map', () => {
    useSvgMaps();
    const { el, render } = renderPage(
      Hotspots,
      stateAfter(hotspotsFeature, HotspotsActions.loaded({ report: report(FIRST_ROWS + 3) })),
    );
    const rows = () => [...el.querySelectorAll('table.hotspots tbody tr')] as HTMLElement[];
    expect(rows()).toHaveLength(FIRST_ROWS);

    // On the map: the smallest circle is drawn last, the last hotspot, beyond the first rows.
    const circles = el.querySelectorAll('.leaflet-interactive');
    circles[circles.length - 1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    render();
    expect(rows()).toHaveLength(FIRST_ROWS + 3);
    expect(el.querySelector('tr.selected')?.getAttribute('data-id')).toBe(String(FIRST_ROWS + 3));

    // In the table; clicking the chosen row again lets it go.
    rows()[1].click();
    render();
    expect(rows()[1].classList).toContain('selected');
    expect(rows()[1].getAttribute('aria-selected')).toBe('true');
    expect(el.querySelectorAll('tr.selected')).toHaveLength(1);
    rows()[1].click();
    render();
    expect(el.querySelector('tr.selected')).toBeNull();
  });

  it('switches the window and line through the URL', () => {
    const { el, store, fixture, setQuery } = renderPage(
      Hotspots,
      stateAfter(hotspotsFeature, HotspotsActions.loaded({ report: report(0) })),
      { from: '2026-11-01T06:00', to: '2026-11-17T06:00' },
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);

    expect(store.dispatch).toHaveBeenCalledWith(
      HotspotsActions.load({ window: CUSTOM, route: null }),
    );
    expect(text(el.querySelector('table.hotspots tbody'))).toContain('No place where buses');
    (el.querySelectorAll('app-time-window .presets button')[6] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { range: '24h', from: null, to: null },
      queryParamsHandling: 'merge',
    });
    const select = el.querySelector(
      'app-time-window select:not(.window-preset)',
    ) as HTMLSelectElement;
    select.value = '9';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { route: '9' },
      queryParamsHandling: 'merge',
    });

    setQuery({ from: '2026-11-01T06:00', to: '2026-11-17T06:00', route: '9' });
    expect(store.dispatch).toHaveBeenLastCalledWith(
      HotspotsActions.load({ window: CUSTOM, route: '9' }),
    );
    const loads = (store.dispatch as unknown as { mock: { calls: unknown[][] } }).mock.calls.length;
    setQuery({ from: '2026-11-01T06:00', to: '2026-11-17T06:00', route: '9', other: 'x' });
    // Same window and route: no reload.
    expect((store.dispatch as unknown as { mock: { calls: unknown[][] } }).mock.calls).toHaveLength(
      loads,
    );
  });

  it('formats times, places, headings and lines', () => {
    expect(lostTime(0.07)).toBe('4 min');
    expect(lostTime(2.43)).toBe('2.4 h');
    expect(waitText(39)).toBe('39 s');
    expect(waitText(80)).toBe('1:20 min');
    expect(nearText(hotspot(1, 1))).toBe('70 m from Częstochowska/Piłsudskiego');
    expect(nearText({ ...hotspot(1, 1), nearestStop: null })).toBe('no stop nearby');
    expect(approachText({ heading: 'NW', share: 0.333 })).toEqual({
      short: '↖ 33%',
      long: '33% heading north-west',
    });
    expect(routesText(['1', '2', '3', '4', '5', '6'].map((route) => ({ route, hours: 1 })))).toBe(
      '1, 2, 3, 4 +2',
    );
    expect(hourText(23)).toBe('23–00');
    expect(radiusFor(4, 4)).toBe(MAX_RADIUS);
    expect(radiusFor(1, 4)).toBeCloseTo(3 + (MAX_RADIUS - 3) / 2);
    expect(radiusFor(1, 0)).toBe(3);
  });
});

describe('hotspots effect', () => {
  const run = (api: Partial<HotspotApi>) => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    load(actions as unknown as Actions, api as HotspotApi).subscribe((a) => out.push(a));
    actions.next(HotspotsActions.load({ window: { from: 'A', to: 'B' }, route: '10' }));
    return out;
  };

  it('loads the report for the window and line, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(api)).toEqual([HotspotsActions.loaded({ report: r })]);
    expect(api.report).toHaveBeenCalledWith({ from: 'A', to: 'B' }, '10');

    const failing = {
      report: () =>
        throwError(() => new HttpErrorResponse({ status: 400, error: { detail: 'Bad window' } })),
    };
    expect(run(failing)).toEqual([HotspotsActions.failure({ error: 'Bad window' })]);
  });
});
