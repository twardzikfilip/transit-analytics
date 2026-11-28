import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { TripAnomalyApi } from '../../core/api/api.services';
import {
  AbnormalTrip,
  StoodThen,
  TripAnomalyKind,
  TripAnomalyRoute,
  TripAnomalyReport,
} from '../../core/models/models';
import { TripAnomaliesActions } from '../../store/trip-anomalies/trip-anomalies.actions';
import { load } from '../../store/trip-anomalies/trip-anomalies.effects';
import { tripAnomaliesFeature } from '../../store/trip-anomalies/trip-anomalies.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { PAGE_ROWS, REFRESH_MS, TripAnomalies, refreshes } from './trip-anomalies';
import { resolveWindow } from '../../shared/time-window/time-window';
import {
  changeFateLabel,
  coverage,
  joinedAt,
  stoodLabel,
  kindLabel,
  shareText,
  replacementFrom,
  signedDelay,
} from './trip-kinds';

const trip = (kind: TripAnomalyKind, route: string, i = 0): AbnormalTrip => {
  const run = kind !== 'missed' && kind !== 'cancelled';
  return {
    kind,
    note: [{ key: 'lostAt', params: { index: 8, total: 25 } }],
    tripId: `${route}-0${i}_P_${i}`,
    route,
    brigade: `0${route}-0${i}`,
    headsign: 'Dworzec',
    firstStop: 'Rynek',
    lastStop: 'Dworzec',
    scheduledDeparture: new Date(Date.parse('2026-11-17T07:00:00Z') - i * 60_000).toISOString(),
    scheduledArrival: new Date(Date.parse('2026-11-17T07:40:00Z') - i * 60_000).toISOString(),
    stops: 25,
    vehicles: kind === 'vehicle-changed' ? ['319', '412'] : run ? ['319'] : [],
    fromStop: run ? 1 : null,
    toStop: run ? 8 : null,
    coveredFrom: run ? 'Rynek' : null,
    coveredTo: run ? 'Lipowa' : null,
    firstSeen: run ? '2026-11-17T07:01:00Z' : null,
    lastSeen: run ? '2026-11-17T07:15:00Z' : null,
    startDelay: run ? 0 : null,
    maxDelay: run ? 135 : null,
    endDelay: run ? -40 : null,
    detourMeters: kind === 'detour' ? 430 : null,
    detourMinutes: kind === 'detour' ? 6 : null,
    detourFrom: kind === 'detour' ? 'Lipowa' : null,
    detourTo: kind === 'detour' ? 'Rynek' : null,
    detourPath:
      kind === 'detour'
        ? [
            [53.13, 23.15],
            [53.131, 23.155],
            [53.133, 23.156],
            [53.135, 23.15],
          ]
        : [],
    detourRoute:
      kind === 'detour'
        ? [
            [53.128, 23.15],
            [53.137, 23.15],
          ]
        : [],
    changeFate: kind === 'vehicle-changed' ? 'not-used' : null,
    replacementRoute: kind === 'vehicle-changed' ? '29' : null,
    replacementBrigade: kind === 'vehicle-changed' ? '029-05' : null,
    changeMinutes: kind === 'vehicle-changed' ? 17 : null,
    delayBeforeChange: kind === 'vehicle-changed' ? 122 : null,
    delayAfterChange: kind === 'vehicle-changed' ? 1035 : null,
    changeStop: kind === 'vehicle-changed' ? 'C' : null,
    joinStop: kind === 'vehicle-changed' ? 'E' : null,
    skippedStops: kind === 'vehicle-changed' ? 1 : null,
    stoodMinutes: kind === 'vehicle-changed' ? 34 : null,
    stoodThen: kind === 'vehicle-changed' ? 'vanished' : null,
    stoodLastSeen: kind === 'vehicle-changed' ? '2026-11-17T07:44:00Z' : null,
    stoodLastSeenMeters: kind === 'vehicle-changed' ? 0 : null,
    timeline:
      kind === 'vehicle-changed'
        ? [
            {
              vehicle: '319',
              kind: 'running',
              from: '2026-11-17T07:01:00Z',
              to: '2026-11-17T07:10:00Z',
            },
            {
              vehicle: '319',
              kind: 'standing',
              from: '2026-11-17T07:10:00Z',
              to: '2026-11-17T07:44:00Z',
            },
            {
              vehicle: '412',
              kind: 'approach',
              from: '2026-11-17T07:20:00Z',
              to: '2026-11-17T07:27:00Z',
            },
            {
              vehicle: '412',
              kind: 'service',
              from: '2026-11-17T07:27:00Z',
              to: '2026-11-17T07:50:00Z',
            },
          ]
        : [],
  };
};

const route = (name: string, judged: number, completed: number, lost = 0): TripAnomalyRoute => ({
  route: name,
  judged,
  completed,
  detours: 0,
  vehicleChanged: 0,
  noSignal: 0,
  shortTurned: judged - completed - lost,
  lost,
  missed: 0,
  cancelled: 0,
});

function report(trips: AbnormalTrip[] = []): TripAnomalyReport {
  return {
    from: '2026-11-16T15:00:00Z',
    to: '2026-11-17T15:00:00Z',
    summary: {
      scheduled: 4000,
      judged: 3500,
      completed: 3400,
      detours: 3,
      vehicleChanged: 2,
      noSignal: 50,
      shortTurned: 30,
      lost: 4,
      missed: 9,
      cancelled: 4,
      notRecorded: 300,
      pending: 200,
      changedNotUsed: 1,
      changedReassigned: 1,
    },
    routes: [route('104', 90, 26, 30), route('12', 150, 146), route('9', 120, 120)],
    tripsTotal: trips.length + 5,
    trips,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('Abnormal trips page', () => {
  it('loads the window in the URL and shows the counts, the lines and the trips', () => {
    const trips = [
      trip('lost', '12'),
      trip('missed', '104', 1),
      trip('detour', '9', 2),
      trip('vehicle-changed', '9', 3),
    ];
    const { el, store } = renderPage(
      TripAnomalies,
      stateAfter(tripAnomaliesFeature, TripAnomaliesActions.loaded({ report: report(trips) })),
      { from: '2026-11-17T09:00', to: '2026-11-17T15:00' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(
      TripAnomaliesActions.load({
        window: {
          from: new Date('2026-11-17T09:00').toISOString(),
          to: new Date('2026-11-17T15:00').toISOString(),
        },
      }),
    );
    expect(text(el.querySelector('app-time-window'))).not.toContain('Refreshes every minute');
    const cards = [...el.querySelectorAll('.cards .stat')].map((c) =>
      ['.label', '.value', '.small'].map((part) => text(c.querySelector(part))),
    );
    expect(cards[0]).toEqual([
      'Scheduled',
      '4000',
      '3500 judged · 300 not recorded · 200 under way',
    ]);
    expect(cards[1]).toEqual(['Run in full', '3400', '97% of judged']);
    expect(cards[4]).toEqual(['Lost', '4', '0.1% of judged']);
    expect(cards.slice(2).map((c) => c[0])).toEqual([
      'Cancelled',
      'Missed',
      'Lost',
      'Short-turned',
      'Detour',
      'Bus changed',
      'No signal',
    ]);

    // Routes with abnormal trips only.
    const routes = [...el.querySelectorAll('table.by-line tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map(text),
    );
    expect(routes).toEqual([
      ['104', '90', '29%', '·', '·', '30', '34', '·', '·', '·'],
      ['12', '150', '97%', '·', '·', '·', '4', '·', '·', '·'],
    ]);

    expect(text(el.querySelector('.card .muted:not(.small)'))).not.toBe('');
    const rows = el.querySelectorAll('table.trips tbody tr:not(.detour-row)');
    expect(rows).toHaveLength(4);
    const cells = rows[0].querySelectorAll('td');
    const lost = [...cells].map(text);
    expect(text(cells[0].querySelector('.tag'))).toBe('Lost');
    expect(text(cells[1].querySelector('.route-badge'))).toBe('12');
    expect(text(cells[1].querySelector('div'))).toBe('012-00');
    expect(text(cells[2].querySelector('div'))).toBe('25 stops');
    expect(cells[2].getAttribute('title')).toBe('Trip 12-00_P_0');
    expect(lost[2]).toContain('Rynek → Dworzec');
    expect(lost[3]).toBe('319');
    expect(lost[4]).toContain('Stops 1–8 of 25');
    expect(cells[4].getAttribute('title')).toBe('Heading to Rynek … Lipowa');
    expect(lost[5]).toBe('0:00 · +2:15 · −0:40');
    expect(lost[6]).toBe('Lost at stop 8/25');
    const missed = [...rows[1].querySelectorAll('td')].map(text);
    expect(missed[3]).toBe('—');
    expect(missed[4]).toBe('Not run');
    expect(missed[5]).toBe('— · — · —');
    expect(text(rows[2].querySelector('td.note div'))).toBe(
      'Detour: 430 m off the route for 6 min, Lipowa → Rynek',
    );
    const changed = [...rows[3].querySelectorAll('td')].map(text);
    expect(text(rows[3].querySelector('.tag'))).toBe('Bus changed');
    expect(changed[3]).toBe('319 → 412 New bus from line 29 (029-05)');
    expect(text(rows[3].querySelector('.fate'))).toBe('Old bus not used again');
    expect([...rows[3].querySelectorAll('.changeover .chip')].map(text)).toEqual([
      'No bus for 17 min',
      'Delay +2:02 → +17:15',
      'Joined 1 stop further on',
      'Old bus stood 34 min, then went silent',
    ]);
    expect(rows[3].querySelector('.chip.skipped')?.getAttribute('title')).toBe(
      'Old bus heading to C; new bus to E',
    );
    expect(rows[0].querySelector('.changeover')).toBeNull();
    expect(text(el.querySelector('.kind-card .changes'))).toBe('1 not used again · 1 reassigned');
    expect(text(el.querySelector('section.card:last-of-type p.muted'))).toContain(
      'the latest 4 of 9 abnormal trips are listed',
    );
  });

  it('filters the trips by kind and line, and shows more', () => {
    const trips = [
      ...Array.from({ length: PAGE_ROWS + 10 }, (_, i) => trip('no-signal', '104', i)),
      trip('lost', '12', 99),
    ];
    const { el, render } = renderPage(
      TripAnomalies,
      stateAfter(tripAnomaliesFeature, TripAnomaliesActions.loaded({ report: report(trips) })),
    );
    const rows = () => el.querySelectorAll('table.trips tbody tr:not(.detour-row)');
    expect(rows()).toHaveLength(PAGE_ROWS);
    ([...el.querySelectorAll('button.more')].pop() as HTMLButtonElement).click();
    render();
    expect(rows()).toHaveLength(PAGE_ROWS + 11);

    // A kind's card: only that kind; again: all.
    const lostCard = [...el.querySelectorAll('button.kind-card')].find((b) =>
      text(b).startsWith('Lost'),
    ) as HTMLButtonElement;
    lostCard.click();
    render();
    expect(rows()).toHaveLength(1);
    expect(lostCard.getAttribute('aria-pressed')).toBe('true');
    expect(text(el.querySelector('section.card:last-of-type p.muted'))).toContain('Lost only');
    lostCard.click();
    render();
    expect(rows()).toHaveLength(PAGE_ROWS);

    // A route, from its row or the menu; "Show all" clears.
    (el.querySelector('table.by-line tbody tr') as HTMLElement).click();
    render();
    expect(rows()).toHaveLength(PAGE_ROWS);
    expect(text(el.querySelector('section.card:last-of-type p.muted'))).toContain('line 104');
    const select = el.querySelector('.filters select') as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toEqual(['All lines', 'Line 12', 'Line 104']);
    select.value = '12';
    select.dispatchEvent(new Event('change'));
    render();
    expect(rows()).toHaveLength(1);
    (el.querySelector('.filters button') as HTMLButtonElement).click();
    render();
    expect(el.querySelector('.filters button')).toBeNull();
    expect(rows()).toHaveLength(PAGE_ROWS);
  });

  it('filters the trips by bus and tells what a replaced bus did after it stopped', () => {
    const changed = {
      ...trip('vehicle-changed', '16', 1),
      vehicles: ['461', '486'],
      stoodMinutes: 34,
      stoodThen: 'vanished' as const,
    };
    const { el, render } = renderPage(
      TripAnomalies,
      stateAfter(
        tripAnomaliesFeature,
        TripAnomaliesActions.loaded({ report: report([changed, trip('lost', '12', 2)]) }),
      ),
    );
    const rows = () => el.querySelectorAll('table.trips tbody tr:not(.detour-row)');
    const bus = el.querySelector('input.bus-filter') as HTMLInputElement;
    expect(
      [...el.querySelectorAll('#trip-anomaly-buses option')].map((o) => o.getAttribute('value')),
    ).toEqual(['319', '461', '486']);
    bus.value = ' 461 ';
    bus.dispatchEvent(new Event('change'));
    render();
    expect(rows()).toHaveLength(1);
    expect(text(el.querySelector('section.card:last-of-type p.muted'))).toContain('bus 461');
    expect(text(rows()[0].querySelector('.changeover'))).toContain(
      'Old bus stood 34 min, then went silent',
    );
    (el.querySelector('.filters button') as HTMLButtonElement).click();
    render();
    expect(rows()).toHaveLength(2);
    expect(bus.value).toBe('');
  });

  it("shows each detour on a map of its own, under its trip: the usual route and the bus's path", async () => {
    useSvgMaps();
    const { el, fixture } = renderPage(
      TripAnomalies,
      stateAfter(
        tripAnomaliesFeature,
        TripAnomaliesActions.loaded({
          report: report([trip('detour', '9', 1), trip('lost', '12', 2)]),
        }),
      ),
    );
    await fixture.whenStable();
    fixture.detectChanges();
    const rows = [...el.querySelectorAll('table.trips tbody tr')];
    expect(rows.map((r) => r.className)).toEqual(['', 'detour-row', '']);
    const map = rows[1].querySelector('app-path-map')!;
    expect(map.querySelector('[role=img]')!.getAttribute('aria-label')).toBe(
      'Line 9: detour Lipowa → Rynek, 430 m off the route',
    );
    // The route in blue; the path, and a dot per report, in orange.
    const strokes = [...map.querySelectorAll('.leaflet-overlay-pane path')].map((p) =>
      p.getAttribute('stroke'),
    );
    expect(strokes).toEqual(['#1e88e5', ...Array(5).fill('#eb6834')]);
    expect([...map.querySelectorAll('.legend li')].map(text)).toEqual([
      'Usual route',
      "The bus's path (dots: its reports)",
    ]);
  });

  it('reloads preset windows of up to 12 hours every minute, as they move on', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-17T12:00:30Z'));
    const { el, store, setQuery } = renderPage(
      TripAnomalies,
      stateAfter(tripAnomaliesFeature, TripAnomaliesActions.loaded({ report: report() })),
    );
    vi.advanceTimersByTime(0);
    const loads = () =>
      (store.dispatch as unknown as { mock: { calls: unknown[][] } }).mock.calls
        .map(([a]) => a as ReturnType<typeof TripAnomaliesActions.load>)
        .filter((a) => a.type === TripAnomaliesActions.load.type);

    // The default, 24 hours, doesn't refresh.
    expect(loads()).toHaveLength(1);
    expect(loads()[0].window.to).toBe('2026-11-17T12:01:00.000Z');
    vi.advanceTimersByTime(REFRESH_MS * 2);
    expect(loads()).toHaveLength(1);
    expect(text(el.querySelector('table.trips tbody'))).toContain('No abnormal trips here.');

    setQuery({ range: '1h' });
    vi.advanceTimersByTime(0);
    expect(loads()).toHaveLength(2);
    expect(text(el.querySelector('app-time-window'))).toContain('Refreshes every minute');
    vi.advanceTimersByTime(REFRESH_MS);
    expect(loads()).toHaveLength(3);
    expect(loads()[2].window.from).toBe('2026-11-17T11:03:40.000Z');
  });

  it('tells which windows refresh and formats the figures', () => {
    const now = new Date('2026-11-17T12:00:00Z');
    const window = (range: string) => resolveWindow({ range, from: null, to: null }, now, '24h');
    expect(refreshes(window('12h'))).toBe(true);
    expect(refreshes(window('24h'))).toBe(false);
    expect(refreshes(window('all'))).toBe(false);
    expect(
      refreshes(
        resolveWindow(
          { range: null, from: '2026-11-17T09:00', to: '2026-11-17T10:00' },
          now,
          '24h',
        ),
      ),
    ).toBe(false);
    expect(shareText(4, 3500)).toBe('0.1%');
    expect(shareText(0, 10)).toBe('0%');
    expect(shareText(1, 0)).toBe('—');
    expect(shareText(3400, 3500)).toBe('97%');
    expect(signedDelay(135)).toBe('+2:15');
    expect(signedDelay(-40)).toBe('−0:40');
    expect(signedDelay(null)).toBe('—');
    expect(coverage(trip('missed', '9'))).toBe('Not run');
    expect(coverage(trip('lost', '9'))).toBe('Stops 1–8 of 25');
    expect(kindLabel('no-signal')).toBe('No signal');
    expect(['not-used', 'reassigned', 'returned'].map((f) => changeFateLabel(f as never))).toEqual([
      'Old bus not used again',
      'Old bus reassigned',
      'Old bus back later',
    ]);
    expect(replacementFrom({ ...trip('vehicle-changed', '9'), replacementRoute: null })).toBe(
      'New bus from the depot',
    );
    expect(joinedAt({ ...trip('vehicle-changed', '9'), skippedStops: 0 })).toBe(
      'Took over at the same place',
    );
    expect(joinedAt({ ...trip('vehicle-changed', '9'), skippedStops: 4 })).toBe(
      'Joined 4 stops further on',
    );
    const stood = (stoodThen: StoodThen, stoodMinutes: number, stoodLastSeenMeters = 0) =>
      stoodLabel({ ...trip('vehicle-changed', '9'), stoodThen, stoodMinutes, stoodLastSeenMeters });
    expect(stood('vanished', 34)).toBe('Old bus stood 34 min, then went silent');
    expect(stood('vanished', 0)).toBe('Old bus went silent right away');
    expect(stood('still-there', 75)).toBe('Old bus still standing there (1 h 15)');
    expect(stood('drove-off', 12, 4230)).toBe('Old bus stood 12 min, then moved off 4.2 km');
    expect(stood('drove-off', 0, 620)).toBe('Old bus moved off 600 m');
    expect(stoodLabel(trip('lost', '9'))).toBeNull();
  });
});

describe('trip anomalies effect', () => {
  const run = (api: Partial<TripAnomalyApi>) => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    load(actions as unknown as Actions, api as TripAnomalyApi).subscribe((a) => out.push(a));
    actions.next(TripAnomaliesActions.load({ window: { from: 'A', to: 'B' } }));
    return out;
  };

  it('loads the report for the window, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(api)).toEqual([TripAnomaliesActions.loaded({ report: r })]);
    expect(api.report).toHaveBeenCalledWith({ from: 'A', to: 'B' });
    const failing = {
      report: () =>
        throwError(() => new HttpErrorResponse({ status: 503, error: { detail: 'Busy' } })),
    };
    expect(run(failing)).toEqual([TripAnomaliesActions.failure({ error: 'Busy' })]);
  });
});
