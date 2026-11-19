import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { BusLaneApi } from '../../core/api/api.services';
import { BusLaneReport, LaneFigures } from '../../core/models/models';
import { BusLanesActions } from '../../store/bus-lanes/bus-lanes.actions';
import { load } from '../../store/bus-lanes/bus-lanes.effects';
import { busLanesFeature } from '../../store/bus-lanes/bus-lanes.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { BusLanesPage } from './bus-lanes';
import { BOTH_WAYS_COLOR, ONE_WAY_COLOR, arrowPoints, mapHeading } from './lane-direction-map';
import {
  NO_SPEED_COLOR,
  delayText,
  headingText,
  linesText,
  slowdownText,
  speedColor,
  speedText,
} from './lane-format';

const figures = (speed: number, delayPerKm: number, km = 500): LaneFigures => ({
  km,
  speed,
  peakSpeed: speed - 1,
  offPeakSpeed: speed,
  delayPerKm,
  peakDelayPerKm: delayPerKm + 2,
  offPeakDelayPerKm: delayPerKm,
  peakSlowdown: (1 / speed) * 100,
});

function report(): BusLaneReport {
  return {
    from: '2026-11-10T13:00:00Z',
    to: '2026-11-17T13:00:00Z',
    days: 7,
    onLanes: figures(17.6, 17.6, 3784),
    sameStreetsWithoutLane: figures(18.4, 9.1, 1200),
    elsewhere: figures(21, -17, 27664),
    hours: [
      {
        hour: 7,
        laneSpeed: 17.1,
        otherSpeed: 19.8,
        laneDelayPerKm: 19.6,
        otherDelayPerKm: -5.3,
        laneKm: 246,
        otherKm: 1787,
      },
      {
        hour: 8,
        laneSpeed: 17.9,
        otherSpeed: 21.1,
        laneDelayPerKm: 13.7,
        otherDelayPerKm: -18.5,
        laneKm: 258,
        otherKm: 1876,
      },
    ],
    stretches: [
      {
        id: 1,
        name: 'Sienkiewicza',
        heading: 'southbound',
        km: 0.53,
        lines: ['3', '6'],
        figures: figures(15.6, 29.9, 100),
        opposite: figures(19.2, 3, 80),
      },
      {
        id: 0,
        name: 'Lipowa',
        heading: 'eastbound',
        km: 0.67,
        lines: ['1', '2', '4', '5', '7', '9', '12', '15', '18', '21'],
        figures: figures(20.4, -4, 300),
        opposite: null,
      },
    ],
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

describe('Bus lanes page', () => {
  it('loads the window in the URL, compares lanes with elsewhere and lists each lane', () => {
    useSvgMaps();
    const { el, store } = renderPage(
      BusLanesPage,
      stateAfter(busLanesFeature, BusLanesActions.loaded({ report: report() })),
      { from: '2026-11-01T06:00', to: '2026-11-17T06:00' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(BusLanesActions.load({ window: CUSTOM }));
    // OpenStreetMap's length against the city's.
    expect(text(el.querySelector('.summary'))).toBe(
      '1.2 km of bus lane on 2 street sections, from OpenStreetMap (19 Nov 2026). The city gives 1 km ' +
        '(City, 28 Jan 2025 ↗), 20% apart.',
    );
    const compare = [...el.querySelectorAll('table.compare tbody tr')].map((r) =>
      [...r.querySelectorAll('th, td')].map(text),
    );
    expect(compare[0]).toEqual([
      'On bus lanes buses on a bus lane',
      '3,784 km',
      '17.6 km/h',
      '16.6 km/h',
      '17.6 km/h',
      '6% slower',
      '+17.6 s/km',
      '+19.6 s/km',
    ]);
    expect(compare.map((r) => r[0])).toEqual([
      'On bus lanes buses on a bus lane',
      'Same streets, other way the streets with bus lanes, in the direction without one',
      'Elsewhere in the city every other street',
    ]);
    // The most driven first; the other way where it has no lane, marked when faster than the lane.
    const rows = [...el.querySelectorAll('table.stretches tbody tr')];
    expect([...rows[0].querySelectorAll('td')].map(text)).toEqual([
      'Lipowa',
      '→ eastbound',
      '0.67 km',
      '300 km',
      '20.4 km/h',
      '19.4 km/h / 20.4 km/h',
      '−4.0 s/km',
      'lane both ways',
      '1, 2, 4, 5, 7, 9 +4',
    ]);
    expect(text(rows[1].querySelector('td.faster'))).toBe('19.2 km/h +3.0 s/km');
    // Both lanes on the map, coloured by speed; the hour charts.
    expect(el.querySelectorAll('app-bus-lane-map .leaflet-interactive')).toHaveLength(2);
    expect(el.querySelectorAll('app-hour-chart')).toHaveLength(2);
  });

  it('sorts the lanes and chooses one in the table or on the map', () => {
    useSvgMaps();
    const { el, render } = renderPage(
      BusLanesPage,
      stateAfter(busLanesFeature, BusLanesActions.loaded({ report: report() })),
    );
    const names = () =>
      [...el.querySelectorAll('table.stretches tbody tr td:first-child')].map(text);
    const select = el.querySelector('.table-head select') as HTMLSelectElement;
    select.value = 'slowest';
    select.dispatchEvent(new Event('change'));
    render();
    expect(names()).toEqual(['Sienkiewicza', 'Lipowa']);
    select.value = 'delay';
    select.dispatchEvent(new Event('change'));
    render();
    expect(names()).toEqual(['Sienkiewicza', 'Lipowa']);

    (el.querySelector('table.stretches tbody tr') as HTMLElement).click();
    render();
    expect(el.querySelector('tr.selected td')?.textContent).toBe('Sienkiewicza');

    const lines = el.querySelectorAll('app-bus-lane-map .leaflet-interactive');
    lines[lines.length - 1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    render();
    expect(el.querySelector('tr.selected')).not.toBeNull();
  });

  it('maps where the lanes are, one way or both ways', () => {
    useSvgMaps();
    const { el } = renderPage(
      BusLanesPage,
      stateAfter(busLanesFeature, BusLanesActions.loaded({ report: report() })),
    );
    const map = el.querySelector('app-lane-direction-map')!;
    const strokes = [...map.querySelectorAll('.leaflet-overlay-pane path')].map((p) =>
      p.getAttribute('stroke'),
    );
    expect(strokes).toEqual([BOTH_WAYS_COLOR, ONE_WAY_COLOR]);
    expect(text(map.querySelector('.legend'))).toContain('Bus lanes both ways');
    // Two short lanes fill the map, zoomed in: arrows, both ways on Lipowa (10) and south on Sienkiewicza (9).
    expect(map.querySelectorAll('.lane-arrow')).toHaveLength(19);
  });

  it('places direction arrows along a lane', () => {
    expect(mapHeading([53.13, 23.15], [53.14, 23.15])).toBeCloseTo(0);
    expect(mapHeading([53.13, 23.16], [53.13, 23.15])).toBeCloseTo(270);
    // 670 m east: five arrows, all pointing east; a short lane gets one, in its middle.
    const arrows = arrowPoints([
      [53.13, 23.15],
      [53.13, 23.16],
    ]);
    expect(arrows).toHaveLength(5);
    expect(arrows.every((a) => Math.abs(a.heading - 90) < 0.01)).toBe(true);
    const one = arrowPoints([
      [53.13, 23.15],
      [53.13, 23.1505],
    ]);
    expect(one).toHaveLength(1);
    expect(one[0].at[1]).toBeCloseTo(23.15025);
    expect(arrowPoints([[53.13, 23.15]])).toEqual([]);
  });

  it('formats speeds, delays, headings and lines', () => {
    expect(speedText(17.64)).toBe('17.6 km/h');
    expect(speedText(null)).toBe('–');
    expect(delayText(4)).toBe('+4.0 s/km');
    expect(delayText(-4)).toBe('−4.0 s/km');
    expect(delayText(0)).toBe('0.0 s/km');
    expect(delayText(undefined)).toBe('–');
    expect(slowdownText({ ...figures(20, 0), peakSlowdown: -3.2 })).toBe('3% faster');
    expect(slowdownText({ ...figures(20, 0), peakSlowdown: 0.2 })).toBe('no slower');
    expect(slowdownText(null)).toBe('–');
    expect(headingText('westbound')).toBe('← westbound');
    expect(linesText(['1', '2'])).toBe('1, 2');
    expect(speedColor(null)).toBe(NO_SPEED_COLOR);
    expect(speedColor(10)).not.toBe(speedColor(30));
  });
});

describe('bus lanes effect', () => {
  const run = (api: Partial<BusLaneApi>) => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    load(actions as unknown as Actions, api as BusLaneApi).subscribe((a) => out.push(a));
    actions.next(BusLanesActions.load({ window: { from: 'A', to: 'B' } }));
    return out;
  };

  it('loads the report for the window, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(api)).toEqual([BusLanesActions.loaded({ report: r })]);
    expect(api.report).toHaveBeenCalledWith({ from: 'A', to: 'B' });

    const failing = {
      report: () =>
        throwError(() => new HttpErrorResponse({ status: 400, error: { detail: 'Bad window' } })),
    };
    expect(run(failing)).toEqual([BusLanesActions.failure({ error: 'Bad window' })]);
  });
});
