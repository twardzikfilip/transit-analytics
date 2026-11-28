import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { DelayGenerationApi } from '../../core/api/api.services';
import {
  DelayExample,
  DelayGenerationReport,
  DelayRouteReport,
  DelayRouteStretch,
  DelayStretch,
} from '../../core/models/models';
import { DelayGenerationActions } from '../../store/delay-generation/delay-generation.actions';
import { load, loadRoute } from '../../store/delay-generation/delay-generation.effects';
import { delayGenerationFeature } from '../../store/delay-generation/delay-generation.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { DelayGeneration, bandStretches } from './delay-generation';
import { changeClass, lateOrEarlyText, minutesPerDay, signedChange } from './delay-scale';
import { activeHours, cellText } from './route-heatmap';
import { offsetLine } from './stretch-map';
import { scheduledTime } from './trip-profile';
import { t } from '../../core/i18n/t';

const BANDS = [
  { key: 'all', label: 'All day' },
  { key: 'am-peak', label: 'Morning peak 06:30–08:30' },
];

const stretch = (from: string, to: string, mean: number, runs = 100): DelayStretch => ({
  from,
  to,
  fromLat: 53.13,
  fromLon: 23.15,
  toLat: 53.133,
  toLon: 23.15,
  routes: ['2', '4'],
  runs: [runs, 4],
  meanSeconds: [mean, 50],
});

function report(): DelayGenerationReport {
  return {
    from: '2026-11-10T13:00:00Z',
    to: '2026-11-17T13:00:00Z',
    days: 4,
    runs: 8520,
    bands: BANDS,
    stretches: [
      stretch('Lipowa', 'Rynek', 36, 712),
      stretch('Rynek', 'Lipowa', 12, 700),
      stretch('Hetmańska', 'Marczukowska', -23, 500),
      stretch('Rare', 'Stretch', 90, 4),
    ],
    routes: [
      {
        route: '10',
        runs: 200,
        meanGainSeconds: 30,
        medianGainSeconds: 20,
        gainingShare: 0.1,
        generatedMinutesPerDay: 25,
        worstFrom: null,
        worstTo: null,
        worstMeanSeconds: null,
      },
      {
        route: '21',
        runs: 370,
        meanGainSeconds: 78.5,
        medianGainSeconds: 79,
        gainingShare: 0.19,
        generatedMinutesPerDay: 121,
        worstFrom: 'Piłsudskiego',
        worstTo: 'Rzeka Biała',
        worstMeanSeconds: 46.3,
      },
    ],
  };
}

const routeStretch = (
  from: string,
  to: string,
  mean: number,
  examples: number[],
): DelayRouteStretch => ({
  from,
  to,
  fromSeq: 1,
  runs: 170,
  meanSeconds: mean,
  medianSeconds: Math.round(mean),
  p90Seconds: 74,
  gainingShare: 0.17,
  delayBefore: 50,
  byHour: Array.from({ length: 24 }, (_, h) => (h === 7 ? mean : h === 8 ? 0 : null)),
  exampleIds: examples,
});

const example = (id: number, vehicle: string, delays: number[]): DelayExample => ({
  id,
  day: '2026-11-16',
  vehicle,
  brigade: '021-02',
  headsign: 'Zielone Wzgórza',
  gainSeconds: delays[delays.length - 1] - delays[0],
  stops: delays.map((d, i) => ({
    stop: ['Piastowska', 'Pałacowa', 'Rzeka Biała', 'Waryńskiego'][i],
    seq: i + 1,
    left: new Date(Date.parse('2026-11-16T15:41:00Z') + i * 120_000).toISOString(),
    delaySeconds: d,
  })),
});

function routeReport(): DelayRouteReport {
  return {
    from: '2026-11-10T13:00:00Z',
    to: '2026-11-17T13:00:00Z',
    route: '21',
    days: 4,
    directions: [
      {
        headsign: 'Zielone Wzgórza',
        runs: 188,
        meanGainSeconds: 58,
        stretches: [
          routeStretch('Piastowska', 'Pałacowa', 21, []),
          routeStretch('Pałacowa', 'Rzeka Biała', 31, [2]),
        ],
        exampleIds: [1, 2],
      },
      { headsign: 'Piastowska', runs: 187, meanGainSeconds: 99, stretches: [], exampleIds: [] },
    ],
    brigades: [{ brigade: '021-06', runs: 35, meanGainSeconds: 164, maxGainSeconds: 384 }],
    examples: [example(1, '477', [0, 60, 120, 240]), example(2, '654', [-9, 0, 125, 130])],
  };
}

function withRoute() {
  return stateAfter(
    delayGenerationFeature,
    DelayGenerationActions.loaded({ report: report() }),
    DelayGenerationActions.routeLoaded({ report: routeReport() }),
  );
}

afterEach(() => {
  // Destroy the page (and its map) first: Leaflet keeps redrawing on animation frames until a map is removed.
  TestBed.resetTestingModule();
});

const URL_WINDOW = { from: '2026-11-10T06:00', to: '2026-11-17T06:00' };
const CUSTOM = {
  from: new Date(URL_WINDOW.from).toISOString(),
  to: new Date(URL_WINDOW.to).toISOString(),
};

describe('Delay generation page', () => {
  it('maps the stretches and lists where delay is created and recovered, and the lines', () => {
    useSvgMaps();
    const { el, store } = renderPage(
      DelayGeneration,
      stateAfter(delayGenerationFeature, DelayGenerationActions.loaded({ report: report() })),
      URL_WINDOW,
    );

    expect(store.dispatch).toHaveBeenCalledWith(DelayGenerationActions.load({ window: CUSTOM }));
    expect(store.dispatch).toHaveBeenCalledWith(DelayGenerationActions.clearRoute());
    expect(text(el.querySelector('.summary'))).toContain('8520 runs measured over 4 days recorded');
    expect(el.querySelectorAll('app-stretch-map path.leaflet-interactive')).toHaveLength(3);

    const created = [...el.querySelectorAll('table.created tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map(text),
    );
    expect(created).toEqual([
      ['1', 'Lipowa → Rynek', '+0:36', '178', '107 min', '2, 4'],
      ['2', 'Rynek → Lipowa', '+0:12', '175', '35 min', '2, 4'],
    ]);
    expect(text(el.querySelector('table.recovered tbody tr td:nth-child(2)'))).toBe(
      'Hetmańska → Marczukowska',
    );
    const routes = [...el.querySelectorAll('table.lines-table tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map(text),
    );
    expect(routes[0]).toEqual([
      '21',
      '370',
      '+1:19',
      '+1:19',
      '19%',
      '121 min',
      'Piłsudskiego → Rzeka Biała (+0:46)',
    ]);
    expect(routes[1][6]).toBe('—');
  });

  it('switches the window, part of the day and line through the URL', () => {
    const { el, store, fixture, setQuery } = renderPage(
      DelayGeneration,
      stateAfter(delayGenerationFeature, DelayGenerationActions.loaded({ report: report() })),
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);

    (el.querySelectorAll('app-time-window .presets button')[9] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { range: '1mo', from: null, to: null },
      queryParamsHandling: 'merge',
    });
    const part = el.querySelector(
      'app-time-window select:not(.window-preset)',
    ) as HTMLSelectElement;
    part.value = 'am-peak';
    part.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { part: 'am-peak' },
      queryParamsHandling: 'merge',
    });
    (el.querySelector('table.lines-table tbody tr') as HTMLElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { route: '21' },
      queryParamsHandling: 'merge',
    });

    setQuery({ ...URL_WINDOW, part: 'am-peak', route: '21' });
    fixture.detectChanges();
    expect(store.dispatch).toHaveBeenCalledWith(DelayGenerationActions.load({ window: CUSTOM }));
    expect(store.dispatch).toHaveBeenLastCalledWith(
      DelayGenerationActions.loadRoute({ window: CUSTOM, route: '21' }),
    );
    // In the morning peak only one stretch was measured 5 times or more.
    expect(text(el.querySelector('table.created tbody'))).toContain('No stretch gains delay');
  });

  it('shows a line: directions, stretches by hour, the runs that gained most and their profile', () => {
    const { el, render } = renderPage(DelayGeneration, withRoute(), { route: '21' });

    const directions = [...el.querySelectorAll('.directions button')].map(text);
    expect(directions).toEqual([
      'Towards Zielone Wzgórza 188 runs, +0:58',
      'Towards Piastowska 187 runs, +1:39',
    ]);
    const rows = el.querySelectorAll('app-route-heatmap tbody tr');
    expect(rows).toHaveLength(2);
    expect(text(rows[1].querySelector('td.stretch'))).toBe(
      'Pałacowa → Rzeka Biała 170 buses · leaving 0:50 late · median +0:31',
    );
    expect([...el.querySelectorAll('app-route-heatmap th.hour')].map(text)).toEqual(['7', '8']);
    expect([...rows[1].querySelectorAll('td.cell')].map(text)).toEqual(['+31', '0']);

    // The direction's runs that gained the most; the first is profiled.
    expect(el.querySelectorAll('table.examples tbody tr')).toHaveLength(2);
    expect(text(el.querySelector('app-trip-profile h3'))).toContain('bus 477');
    expect(el.querySelectorAll('app-trip-profile circle')).toHaveLength(4);

    // A stretch: its runs, the stretch drawn bold in the profile.
    (rows[1] as HTMLElement).click();
    render();
    expect(text(el.querySelector('.examples-row h3'))).toBe(
      'Runs that gained the most on Pałacowa → Rzeka Biała',
    );
    expect(el.querySelectorAll('table.examples tbody tr')).toHaveLength(1);
    expect(text(el.querySelector('app-trip-profile h3'))).toContain('bus 654');
    expect(el.querySelectorAll('app-trip-profile line.chosen')).toHaveLength(1);
    const changes = [...el.querySelectorAll('app-trip-profile tbody tr td:last-child')].map(text);
    expect(changes).toEqual(['', '+0:09', '+2:05', '+0:05']);
    expect(el.querySelector('app-trip-profile tr.chosen-row td')?.textContent).toContain(
      'Rzeka Biała',
    );

    // The other direction has no runs to show.
    (el.querySelectorAll('.directions button')[1] as HTMLButtonElement).click();
    render();
    expect(el.querySelectorAll('app-route-heatmap tbody tr')).toHaveLength(0);
    expect(text(el.querySelector('table.examples tbody'))).toContain('No run gained');
    expect([...el.querySelectorAll('table.brigades tbody tr td')].map(text)).toEqual([
      '021-06',
      '35',
      '+2:44',
      '+6:24',
    ]);
  });

  it('closes the line and shows its loading and errors', () => {
    const loading = stateAfter(
      delayGenerationFeature,
      DelayGenerationActions.loaded({ report: report() }),
      DelayGenerationActions.loadRoute({ window: CUSTOM, route: '99' }),
    );
    const { el, fixture } = renderPage(DelayGeneration, loading, { route: '99' });
    expect(text(el.querySelector('.line-detail [role=status]'))).toBe('Loading line 99…');
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    (el.querySelector('.line-head button') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { route: null },
      queryParamsHandling: 'merge',
    });
  });

  it('shows a line without runs, and a failure', () => {
    const empty = stateAfter(
      delayGenerationFeature,
      DelayGenerationActions.routeLoaded({ report: { ...routeReport(), directions: [] } }),
      DelayGenerationActions.routeFailure({ error: 'Server error' }),
    );
    const { el } = renderPage(DelayGeneration, empty, { route: '21' });
    // The error itself is a toast (core/api/error-toasts.ts), not text on the page.
    expect(text(el)).not.toContain('Server error');
    expect(text(el.querySelector('.line-detail'))).toContain('No runs of this line were measured');
  });
});

describe('delay generation helpers', () => {
  it('colours, signs and totals changes in delay', () => {
    expect(changeClass(-75).color).toBe('#104281');
    expect(changeClass(-10).color).toBe('#d4d3ce');
    expect(changeClass(9.9).color).toBe('#d4d3ce');
    expect(changeClass(45).dark).toBe(true);
    expect(t(changeClass(600).label)).toBe('gains 1 min or more');
    expect(signedChange(35)).toBe('+0:35');
    expect(signedChange(-65)).toBe('−1:05');
    expect(signedChange(0.4)).toBe('0:00');
    expect(lateOrEarlyText(135)).toBe('2:15 late');
    expect(lateOrEarlyText(-40)).toBe('0:40 early');
    expect(lateOrEarlyText(0)).toBe('on time');
    expect(minutesPerDay(30, 400, 4)).toBe(50);
    expect(minutesPerDay(30, 400, 0)).toBe(200);
    expect(scheduledTime('2026-11-16T15:41:00Z', 120)).toBe('16:39');
    expect(activeHours([routeStretch('A', 'B', 10, [])])).toEqual([7, 8]);
    expect([cellText(31.4), cellText(-0.3), cellText(-6.2)]).toEqual(['+31', '0', '−6']);
    expect(bandStretches(report(), 1).map((s) => s.stretch.from)).toEqual([]);
    expect(bandStretches(report(), 0)).toHaveLength(3);
  });

  it('draws each direction to the right of its way', () => {
    // Heading north: the route moves east (greater longitude), at the same latitude.
    const [a, b] = offsetLine([53.13, 23.15], [53.14, 23.15], 3, 14);
    expect(a[0]).toBeCloseTo(53.13, 6);
    expect(a[1]).toBeGreaterThan(23.15);
    expect(b[1]).toBeCloseTo(a[1], 9);
    // Heading south: west.
    const [c] = offsetLine([53.14, 23.15], [53.13, 23.15], 3, 14);
    expect(c[1]).toBeLessThan(23.15);
    // Twice the zoom, half the distance.
    const [far] = offsetLine([53.13, 23.15], [53.14, 23.15], 3, 13);
    expect(far[1] - 23.15).toBeCloseTo(2 * (a[1] - 23.15), 9);
  });
});

describe('delay generation effects', () => {
  type Effect = (actions: Actions, api: DelayGenerationApi) => Observable<unknown>;
  const run = (effect: Effect, api: Partial<DelayGenerationApi>, action: unknown) => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    effect(actions as unknown as Actions, api as DelayGenerationApi).subscribe((a) => out.push(a));
    actions.next(action);
    return out;
  };
  const failing = () =>
    throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Down' } }));

  it('loads the network, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(load, api, DelayGenerationActions.load({ window: { from: 'A', to: 'B' } }))).toEqual(
      [DelayGenerationActions.loaded({ report: r })],
    );
    expect(api.report).toHaveBeenCalledWith({ from: 'A', to: 'B' });
    expect(
      run(
        load,
        { report: failing },
        DelayGenerationActions.load({ window: { from: 'A', to: 'B' } }),
      ),
    ).toEqual([DelayGenerationActions.failure({ error: 'Down' })]);
  });

  it('loads a line, reports a failure, and does nothing on clear', () => {
    const r = routeReport();
    const api = { route: vi.fn(() => of(r)) };
    const action = DelayGenerationActions.loadRoute({
      window: { from: 'A', to: 'B' },
      route: '21',
    });
    expect(run(loadRoute, api, action)).toEqual([
      DelayGenerationActions.routeLoaded({ report: r }),
    ]);
    expect(api.route).toHaveBeenCalledWith({ from: 'A', to: 'B' }, '21');
    expect(run(loadRoute, { route: failing }, action)).toEqual([
      DelayGenerationActions.routeFailure({ error: 'Down' }),
    ]);
    expect(run(loadRoute, api, DelayGenerationActions.clearRoute())).toEqual([]);
  });
});
