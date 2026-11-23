import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { BrigadeApi } from '../../core/api/api.services';
import {
  ActualRun,
  BrigadeDay,
  BrigadeList,
  BrigadeRef,
  BrigadeTrip,
} from '../../core/models/models';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { loadDay, loadList } from '../../store/brigades/brigades.effects';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { BrigadeDayChart, fit } from './brigade-day-chart';
import {
  TRIP_COLORS,
  actualSpan,
  busesText,
  departureDelayText,
  share,
  shortStop,
} from './brigade-format';
import { t } from '../../core/i18n/t';
import { PageBack } from '../../core/title/page-back';
import { BrigadePage } from './brigade';
import { BrigadesPage, groupByLine } from './brigades';

const at = (hhmm: string) => `2026-11-18T${hhmm}:00Z`;

function run(
  bus: string,
  from: string,
  to: string,
  startDelay: number | null,
  fromStop = 1,
  toStop = 20,
): ActualRun {
  return {
    bus,
    fromStop,
    toStop,
    fromName: 'A',
    toName: 'B',
    departure: at(from),
    arrival: at(to),
    startDelay,
    endDelay: startDelay,
  };
}

function trip(
  id: string,
  from: string,
  to: string,
  kind: BrigadeTrip['kind'],
  runs: ActualRun[] = [],
): BrigadeTrip {
  return {
    tripId: id,
    line: '103',
    headsign: 'Choroszcz/Cmentarz',
    from: 'Piastowska',
    to: 'Choroszcz/Cmentarz',
    direction: 0,
    departure: at(from),
    arrival: at(to),
    stops: 20,
    kind,
    note: null,
    runs,
    missing: [],
  };
}

const LIST: BrigadeList = {
  agency: 'BKM',
  agencies: [
    { id: 'BKM', name: 'BKM' },
    { id: 'X', name: 'Other' },
    { id: 'wschodexpress', name: 'Gmina Dobrzyniewo Duże, Gmina Zabłudów' },
  ],
  brigades: [
    { brigade: '103-05', lines: ['103'] },
    { brigade: '016-02', lines: ['16'] },
  ],
};

function day(): BrigadeDay {
  const lost = trip('T3', '07:00', '07:40', 'lost', [run('209', '07:01', '07:20', 60, 1, 9)]);
  lost.missing = [{ fromStop: 9, toStop: 20, from: at('07:20'), to: at('07:40'), why: 'not-run' }];
  lost.note = [
    {
      key: 'lostForGood',
      params: { bus: '209', stop: 'Lipowa', index: 8, total: 25, time: '07:20' },
    },
  ];
  return {
    brigade: '103-05',
    serviceDay: '2026-11-18',
    summary: {
      trips: 4,
      judged: 3,
      completed: 1,
      abnormal: 2,
      onTime: 2,
      withStartDelay: 3,
      medianStartDelay: 60,
      maxDelay: 400,
      buses: ['209', '486'],
    },
    trips: [
      trip('T1', '05:00', '05:40', 'completed', [run('209', '05:00', '05:41', 10)]),
      trip('T2', '06:00', '06:40', 'vehicle-changed', [
        run('209', '06:02', '06:15', 120, 1, 8),
        run('486', '06:25', '06:45', 300, 9, 20),
      ]),
      lost,
      trip('T4', '08:20', '09:00', 'pending'),
    ],
  };
}

describe('Brigades page', () => {
  it("loads every brigade of the URL's agency", () => {
    const { store } = renderPage(BrigadesPage, {}, { agency: 'X' });
    expect(store.dispatch).toHaveBeenCalledWith(BrigadesActions.loadList({ agency: 'X' }));
  });

  it("opens a brigade's own page, keeping the agency for the way back", () => {
    const { el, fixture } = renderPage(
      BrigadesPage,
      stateAfter(brigadesFeature, BrigadesActions.listLoaded({ list: LIST })),
      { agency: 'BKM' },
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    (el.querySelectorAll('.brigade-chips button')[1] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenCalledWith(['/brigades', '103-05'], {
      queryParams: { agency: 'BKM' },
    });
  });

  it('groups brigades by their own line, in natural order', () => {
    const ref = (brigade: string, lines: string[]): BrigadeRef => ({ brigade, lines });
    const groups = groupByLine([
      ref('103-10', ['103']),
      ref('N1-01', ['N1']),
      ref('002-01', ['100', '2']),
      ref('103-02', ['103', '3']),
      ref('010-01', ['10']),
    ]);
    expect(groups.map((g) => [g.line, g.brigades.map((b) => b.brigade)])).toEqual([
      ['2', ['002-01']],
      ['10', ['010-01']],
      ['103', ['103-02', '103-10']],
      ['N1', ['N1-01']],
    ]);
  });

  it('offers the brigades to pick from, with the agencies', () => {
    const { el, store } = renderPage(
      BrigadesPage,
      stateAfter(brigadesFeature, BrigadesActions.listLoaded({ list: LIST })),
    );
    expect(store.dispatch).not.toHaveBeenCalledWith(
      expect.objectContaining({ brigade: expect.anything() }),
    );
    // One row per line, lines in natural order; every brigade shown.
    const lines = [...el.querySelectorAll('.brigade-lines dt .route-badge')];
    expect(lines.map(text)).toEqual(['16', '103']);
    expect(lines.map((l) => l.getAttribute('aria-label'))).toEqual(['Line 16', 'Line 103']);
    expect([...el.querySelectorAll('.brigade-chips button')].map(text)).toEqual([
      '016-02',
      '103-05',
    ]);
    expect(el.querySelectorAll('datalist option')).toHaveLength(2);
    expect(text(el.querySelector('datalist option'))).toContain('line 103');
    expect([...el.querySelectorAll('select option')].map(text)).toEqual([
      'BKM',
      'Other',
      'Wschód Express',
    ]);
    // No day to pick until a brigade is chosen: the list is every brigade, whatever day it runs.
    expect(el.querySelector('input[type=date]')).toBeNull();
    expect(el.querySelector('.controls input[type=search]')).not.toBeNull();
    expect(text(el.querySelector('.pick'))).not.toContain('Pick a brigade');
  });
});

describe('Brigade page', () => {
  it("loads the brigade in the path on the URL's day", () => {
    const { store } = renderPage(BrigadePage, {}, { date: '2026-11-18' }, [], {
      brigade: '103-05',
    });
    expect(store.dispatch).toHaveBeenCalledWith(
      BrigadesActions.loadDay({ brigade: '103-05', date: '2026-11-18' }),
    );
  });

  it("shows the brigade's day: tiles, the timeline and every trip", () => {
    const { el, fixture } = renderPage(
      BrigadePage,
      stateAfter(brigadesFeature, BrigadesActions.dayLoaded({ day: day() })),
      { date: '2026-11-18', agency: 'BKM' },
      [],
      { brigade: '103-05' },
    );
    // At the top only the day; the way back is in the header, and the title (there too) names the brigade.
    const controls = el.querySelector('.day-controls')!;
    expect((controls.querySelector('input[type=date]') as HTMLInputElement).value).toBe(
      '2026-11-18',
    );
    expect(controls.querySelector('button')).toBeNull();
    expect(text(el)).not.toContain('service day 2026-11-18');
    expect(el.querySelector('input[type=search]')).toBeNull();
    expect(el.querySelector('select')).toBeNull();
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const back = TestBed.inject(PageBack).link()!;
    expect(t(back.label)).toBe('Back to all brigades');
    back.go();
    expect(navigate).toHaveBeenCalledWith(['/brigades'], { queryParams: { agency: 'BKM' } });
    // Gone with the page.
    fixture.destroy();
    expect(TestBed.inject(PageBack).link()).toBeNull();
    expect(text(el.querySelector('.tile.alert strong'))).toBe('2');
    expect(text(el.querySelector('.tiles'))).toContain('209, 486');
    expect(text(el.querySelector('.tiles'))).toContain('the bus changed during the day');
    expect(el.querySelector('app-brigade-day-chart svg')).not.toBeNull();
    const rows = el.querySelectorAll('table.trips tbody tr');
    expect(rows).toHaveLength(4);
    expect(text(rows[1])).toContain('209 → 486');
    expect(text(rows[1].querySelector('.outcome.problem'))).toBe('Bus changed');
    expect(text(rows[2])).toContain('Not run: stops 9–20');
    expect(text(rows[3].querySelector('.outcome'))).toBe('Upcoming');
  });

  it("doesn't show a previous brigade's day while another loads", () => {
    const { el } = renderPage(
      BrigadePage,
      stateAfter(brigadesFeature, BrigadesActions.dayLoaded({ day: day() })),
      {},
      [],
      { brigade: '016-02' },
    );
    expect(el.querySelector('app-brigade-day-chart')).toBeNull();
  });
});

describe('Brigade day chart', () => {
  function chart(trips: BrigadeTrip[]) {
    const fixture = TestBed.createComponent(BrigadeDayChart);
    fixture.componentRef.setInput('trips', trips);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws each trip scheduled, in alternating colours, and what ran under it', () => {
    const el = chart(day().trips);
    const scheduled = [...el.querySelectorAll('rect.scheduled')];
    expect(scheduled).toHaveLength(4);
    expect(scheduled.map((r) => r.getAttribute('fill'))).toEqual([
      TRIP_COLORS[0],
      TRIP_COLORS[1],
      TRIP_COLORS[0],
      TRIP_COLORS[1],
    ]);
    // A bar per bus: one, two (changed), one (lost).
    expect(el.querySelectorAll('rect.actual')).toHaveLength(4);
    expect(el.querySelectorAll('.marker')).toHaveLength(2); // the change and the loss
    expect(el.querySelector('rect.missing')?.getAttribute('fill')).toBe('url(#not-run)');
    expect(el.querySelectorAll('rect.no-data')).toHaveLength(1); // upcoming
    expect(el.querySelectorAll('line.connector')).toHaveLength(3);
    expect([...el.querySelectorAll('text.label.bus')].map(text)).toContain('209 → 486 +2:00');
  });

  it('describes a hovered trip', () => {
    const fixture = TestBed.createComponent(BrigadeDayChart);
    fixture.componentRef.setInput('trips', day().trips);
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement)
      .querySelectorAll('g.trip')[2]
      .dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    const tip = text((fixture.nativeElement as HTMLElement).querySelector('.tooltip'));
    expect(tip).toContain('103 → Choroszcz/Cmentarz');
    expect(tip).toContain('Lost');
    expect(tip).toContain('bus 209');
    expect(tip).toContain(
      "Bus 209 stopped reporting heading to Lipowa (stop 8 of 25) at 07:20 and wasn't seen again",
    );
  });

  it('fits labels into the room they have', () => {
    expect(fit('103 → Choroszcz', 200)).toBe('103 → Choroszcz');
    expect(fit('103 → Choroszcz', 50)).toBe('103 → …');
    expect(fit('103 → Choroszcz', 20)).toBe('');
  });
});

describe('Brigade formats', () => {
  it('formats delays, spans, buses, shares and stop names', () => {
    expect(departureDelayText(null)).toBe('–');
    expect(departureDelayText(20)).toBe('on time');
    expect(departureDelayText(90)).toBe('+1:30');
    expect(departureDelayText(-45)).toBe('−0:45');
    expect(busesText(day().trips[1])).toBe('209 → 486');
    expect(busesText(day().trips[3])).toBe('–');
    expect(actualSpan(day().trips[3])).toBe('–');
    expect(share(2, 3)).toBe('67%');
    expect(share(0, 0)).toBe('–');
    expect(shortStop('Choroszcz/Cmentarz')).toBe('Cmentarz');
  });
});

describe('Brigades effects', () => {
  it('loads the list and a day, or reports the failure', () => {
    const list = new Subject<unknown>();
    const out: unknown[] = [];
    const api = {
      list: vi.fn(() => of(LIST)),
      day: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 404, error: { detail: 'No such' } })),
      ),
    };
    loadList(list as unknown as Actions, api as unknown as BrigadeApi).subscribe((a) =>
      out.push(a),
    );
    loadDay(list as unknown as Actions, api as unknown as BrigadeApi).subscribe((a) => out.push(a));
    list.next(BrigadesActions.loadList({ agency: 'BKM' }));
    list.next(BrigadesActions.loadDay({ brigade: '103-05', date: null }));
    expect(api.list).toHaveBeenCalledWith('BKM');
    expect(api.day).toHaveBeenCalledWith('103-05', null);
    expect(out).toEqual([
      BrigadesActions.listLoaded({ list: LIST }),
      BrigadesActions.failure({ error: 'No such' }),
    ]);
  });
});
