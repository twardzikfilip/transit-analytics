import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { TripAnomalyApi } from '../../core/api/api.services';
import {
  AbnormalTrip,
  TripAnomalyKind,
  TripAnomalyLine,
  TripAnomalyReport,
} from '../../core/models/models';
import { TripAnomaliesActions } from '../../store/trip-anomalies/trip-anomalies.actions';
import { load } from '../../store/trip-anomalies/trip-anomalies.effects';
import { tripAnomaliesFeature } from '../../store/trip-anomalies/trip-anomalies.reducer';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { PAGE_ROWS, REFRESH_MS, TripAnomalies } from './trip-anomalies';
import {
  changeFateLabel,
  coverage,
  joinedAt,
  kindLabel,
  parseTripWindow,
  percent,
  replacementFrom,
  signedDelay,
} from './trip-kinds';

const trip = (kind: TripAnomalyKind, line: string, i = 0): AbnormalTrip => {
  const run = kind !== 'missed' && kind !== 'cancelled';
  return {
    kind,
    note: `${kind} note`,
    tripId: `${line}-0${i}_P_${i}`,
    line,
    brigade: `0${line}-0${i}`,
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
    changeFate: kind === 'vehicle-changed' ? 'broken-down' : null,
    replacementLine: kind === 'vehicle-changed' ? '29' : null,
    replacementBrigade: kind === 'vehicle-changed' ? '029-05' : null,
    changeMinutes: kind === 'vehicle-changed' ? 17 : null,
    delayBeforeChange: kind === 'vehicle-changed' ? 122 : null,
    delayAfterChange: kind === 'vehicle-changed' ? 1035 : null,
    changeStop: kind === 'vehicle-changed' ? 'C' : null,
    joinStop: kind === 'vehicle-changed' ? 'E' : null,
    skippedStops: kind === 'vehicle-changed' ? 1 : null,
  };
};

const line = (name: string, judged: number, completed: number, lost = 0): TripAnomalyLine => ({
  line: name,
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
    window: 'day',
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
      changedBrokenDown: 1,
      changedReassigned: 1,
    },
    lines: [line('104', 90, 26, 30), line('12', 150, 146), line('9', 120, 120)],
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
      { window: '6h' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(TripAnomaliesActions.load({ window: '6h' }));
    expect(text(el.querySelector('.controls'))).toContain('Refreshes every minute');
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

    // Lines with abnormal trips only.
    const lines = [...el.querySelectorAll('table.by-line tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map(text),
    );
    expect(lines).toEqual([
      ['104', '90', '29%', '·', '·', '30', '34', '·', '·', '·'],
      ['12', '150', '97%', '·', '·', '·', '4', '·', '·', '·'],
    ]);

    expect(text(el.querySelector('.card .muted:not(.small)'))).not.toBe('');
    const rows = el.querySelectorAll('table.trips tbody tr');
    expect(rows).toHaveLength(4);
    const cells = rows[0].querySelectorAll('td');
    const lost = [...cells].map(text);
    expect(text(cells[0].querySelector('.tag'))).toBe('Lost');
    expect(text(cells[1].querySelector('strong'))).toBe('12');
    expect(text(cells[1].querySelector('div'))).toBe('012-00');
    expect(text(cells[2].querySelector('div'))).toBe('25 stops');
    expect(cells[2].getAttribute('title')).toBe('Trip 12-00_P_0');
    expect(lost[2]).toContain('Rynek → Dworzec');
    expect(lost[3]).toBe('319');
    expect(lost[4]).toContain('Stops 1–8 of 25');
    expect(cells[4].getAttribute('title')).toBe('Heading to Rynek … Lipowa');
    expect(lost[5]).toBe('0:00 · +2:15 · −0:40');
    expect(lost[6]).toBe('lost note');
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
    expect(text(rows[3].querySelector('.fate'))).toBe('Possibly broken down');
    expect([...rows[3].querySelectorAll('.changeover .chip')].map(text)).toEqual([
      'No bus for 17 min',
      'Delay +2:02 → +17:15',
      'Joined 1 stop further on',
    ]);
    expect(rows[3].querySelector('.chip.skipped')?.getAttribute('title')).toBe(
      'Old bus heading to C; new bus to E',
    );
    expect(rows[0].querySelector('.changeover')).toBeNull();
    expect(text(el.querySelector('.kind-card .changes'))).toBe(
      '1 possibly broken down · 1 reassigned',
    );
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
    const rows = () => el.querySelectorAll('table.trips tbody tr');
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

    // A line, from its row or the menu; "Show all" clears.
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

  it('switches the window through the URL and refreshes short windows every minute', () => {
    vi.useFakeTimers();
    const { el, store, fixture, setQuery } = renderPage(
      TripAnomalies,
      stateAfter(tripAnomaliesFeature, TripAnomaliesActions.loaded({ report: report() })),
    );
    vi.advanceTimersByTime(0);
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const loads = () =>
      (store.dispatch as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(
        ([a]) => (a as { type: string }).type === TripAnomaliesActions.load.type,
      ).length;

    expect(loads()).toBe(1);
    vi.advanceTimersByTime(REFRESH_MS * 2);
    expect(loads()).toBe(1);
    expect(text(el.querySelector('table.trips tbody'))).toContain('No abnormal trips here.');

    const buttons = [...el.querySelectorAll('.windows button')] as HTMLButtonElement[];
    expect(buttons.map(text)).toEqual([
      '15 min',
      '1 hour',
      '3 hours',
      '6 hours',
      '24 hours',
      '7 days',
      '30 days',
    ]);
    buttons[0].click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { window: '15m' },
      queryParamsHandling: 'merge',
    });
    buttons[4].click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { window: null },
      queryParamsHandling: 'merge',
    });

    setQuery({ window: '15m' });
    vi.advanceTimersByTime(0);
    expect(store.dispatch).toHaveBeenLastCalledWith(TripAnomaliesActions.load({ window: '15m' }));
    expect(loads()).toBe(2);
    vi.advanceTimersByTime(REFRESH_MS);
    expect(loads()).toBe(3);
  });

  it('reads the window and formats the figures', () => {
    expect(parseTripWindow('3h')).toBe('3h');
    expect(parseTripWindow('year')).toBe('day');
    expect(parseTripWindow(null)).toBe('day');
    expect(percent(4, 3500)).toBe('0.1%');
    expect(percent(0, 10)).toBe('0%');
    expect(percent(1, 0)).toBe('—');
    expect(percent(3400, 3500)).toBe('97%');
    expect(signedDelay(135)).toBe('+2:15');
    expect(signedDelay(-40)).toBe('−0:40');
    expect(signedDelay(null)).toBe('—');
    expect(coverage(trip('missed', '9'))).toBe('Not run');
    expect(coverage(trip('lost', '9'))).toBe('Stops 1–8 of 25');
    expect(kindLabel('no-signal')).toBe('No signal');
    expect(
      ['broken-down', 'reassigned', 'returned'].map((f) => changeFateLabel(f as never)),
    ).toEqual(['Possibly broken down', 'Old bus reassigned', 'Old bus back later']);
    expect(replacementFrom({ ...trip('vehicle-changed', '9'), replacementLine: null })).toBe(
      'New bus from the depot',
    );
    expect(joinedAt({ ...trip('vehicle-changed', '9'), skippedStops: 0 })).toBe(
      'Took over at the same place',
    );
    expect(joinedAt({ ...trip('vehicle-changed', '9'), skippedStops: 4 })).toBe(
      'Joined 4 stops further on',
    );
  });
});

describe('trip anomalies effect', () => {
  const run = (api: Partial<TripAnomalyApi>) => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    load(actions as unknown as Actions, api as TripAnomalyApi).subscribe((a) => out.push(a));
    actions.next(TripAnomaliesActions.load({ window: '1h' }));
    return out;
  };

  it('loads the report for the window, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(api)).toEqual([TripAnomaliesActions.loaded({ report: r })]);
    expect(api.report).toHaveBeenCalledWith('1h');
    const failing = {
      report: () =>
        throwError(() => new HttpErrorResponse({ status: 503, error: { detail: 'Busy' } })),
    };
    expect(run(failing)).toEqual([TripAnomaliesActions.failure({ error: 'Busy' })]);
  });
});
