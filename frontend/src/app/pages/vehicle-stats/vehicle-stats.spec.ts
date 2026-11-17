import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Subject, of, throwError } from 'rxjs';
import { VehicleStatsApi } from '../../core/api/api.services';
import {
  IdleVehicle,
  StatsVehicle,
  VehicleIncident,
  VehicleStatsReport,
  VehicleUsage,
} from '../../core/models/models';
import { VehicleStatsActions } from '../../store/vehicle-stats/vehicle-stats.actions';
import { load } from '../../store/vehicle-stats/vehicle-stats.effects';
import { vehicleStatsFeature } from '../../store/vehicle-stats/vehicle-stats.reducer';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { pickerLabel, toggleAgency } from './agency-picker';
import {
  FIRST_ROWS,
  VehicleStats,
  idleFor,
  lastSeenNote,
  parseWindow,
  whatHappened,
} from './vehicle-stats';

const bus = (id: number, number: string): StatsVehicle => ({
  id,
  number,
  model: 'Solaris Urbino 12',
  agency: 'BKM',
});
const usage = (v: StatsVehicle, hours: number, lines: string[]): VehicleUsage => ({
  vehicle: v,
  hours,
  days: 3,
  lines,
  firstSeen: '2026-11-14T05:00:00Z',
  lastSeen: '2026-11-16T20:00:00Z',
});
const incident = (v: StatsVehicle, kind: 'switched' | 'disappeared'): VehicleIncident => ({
  day: '2026-11-16',
  vehicle: v,
  kind,
  line: '9',
  brigade: '009-02',
  lastSeen: '2026-11-16T15:15:00Z',
  lastStop: 'Rynek',
  delaySeconds: 120,
  scheduledUntil: '2026-11-16T17:00:00Z',
  scheduledEnd: '2026-11-16T21:21:00Z',
  missedRuns: 3,
  nextLine: kind === 'switched' ? '10' : null,
  nextBrigade: kind === 'switched' ? '010-04' : null,
  nextSeen: kind === 'switched' ? '2026-11-16T15:40:00Z' : null,
  replacedBy: kind === 'disappeared' ? bus(9, '261') : null,
  replacedAt: kind === 'disappeared' ? '2026-11-16T15:21:00Z' : null,
});

const idle = (id: number, lastSeen: string | null): IdleVehicle => ({
  vehicle: bus(id, `${id}`),
  lastSeen,
  lastLine: lastSeen ? '4' : null,
});

function report(days = 1, unused = 2): VehicleStatsReport {
  const a = bus(1, '230');
  const b = bus(2, '009');
  return {
    window: 'week',
    from: '2026-11-09T20:00:00Z',
    to: '2026-11-16T20:00:00Z',
    vehicles: 2,
    agencies: [
      { name: 'BKM', vehicles: 2 },
      { name: 'TUROSN', vehicles: 1 },
    ],
    mostUsed: [usage(a, 37.6, ['11', '19']), usage(b, 12.5, ['9'])],
    leastUsed: [usage(b, 12.5, ['9']), usage(a, 37.6, ['11', '19'])],
    mostLines: [usage(a, 37.6, ['11', '19'])],
    fewestLines: [usage(b, 12.5, ['9'])],
    multiLineDays: Array.from({ length: days }, (_, i) => ({
      day: `2026-11-${String(16 - (i % 7)).padStart(2, '0')}`,
      vehicle: bus(100 + i, `${100 + i}`),
      lines: [
        {
          line: '11',
          brigade: '011-03',
          from: '2026-11-16T04:00:00Z',
          to: '2026-11-16T09:00:00Z',
          hours: 5,
        },
        {
          line: '19',
          brigade: '019-01',
          from: '2026-11-16T10:00:00Z',
          to: '2026-11-16T15:00:00Z',
          hours: 4.5,
        },
      ],
    })),
    incidents: [incident(a, 'switched'), incident(b, 'disappeared')],
    unused: Array.from({ length: unused }, (_, i) =>
      idle(500 + i, i === unused - 1 ? null : '2026-11-12T07:30:00Z'),
    ),
  };
}

describe('Vehicles in detail page', () => {
  it('loads the window in the URL and shows the top 10s with model and operator', () => {
    const { el, store } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature, VehicleStatsActions.loaded({ report: report() })),
      { window: 'month' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(
      VehicleStatsActions.load({ window: 'month', agencies: [] }),
    );
    const tables = [...el.querySelectorAll('app-usage-table')];
    expect(tables.map((t) => text(t.querySelector('h2')))).toEqual([
      'Top 10 most used',
      'Top 10 least used',
      'Top 10 on the most lines',
      'Top 10 on the fewest lines',
    ]);
    const first = [...tables[0].querySelectorAll('tbody tr')[0].querySelectorAll('td')].map(text);
    expect(first).toEqual(['1', '230', 'Solaris Urbino 12', 'BKM', '37.6', '3', '11, 19']);
    const byLines = [...tables[2].querySelectorAll('tbody tr')[0].querySelectorAll('td')].map(text);
    expect(byLines).toEqual(['1', '230', 'Solaris Urbino 12', 'BKM', '2', '11, 19', '37.6']);
  });

  it('lists vehicles on several lines a day and those that left their brigade', () => {
    const { el } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature, VehicleStatsActions.loaded({ report: report() })),
    );

    const day = el.querySelector('table.line-days tbody tr')!;
    expect(text(day.querySelector('ol.spells'))).toContain('11 05:00–10:00, 5.0 h, brigade 011-03');
    const incidents = [...el.querySelectorAll('table.incidents tbody tr')];
    expect(incidents).toHaveLength(2);
    expect(text(incidents[0])).toContain('to line 10 (010-04) at 16:40');
    expect(text(incidents[0].querySelector('.note'))).toBe('before Rynek, 2:00 min late');
    expect(text(incidents[1].querySelector('.tag.danger'))).toBe('Disappeared');
    expect(text(incidents[1].querySelector('td:last-child'))).toBe('261 at 16:21');
  });

  it('lists the buses not used at all in the window, with when they last served a line', () => {
    const { el } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature, VehicleStatsActions.loaded({ report: report(1, 3) })),
    );
    const section = el.querySelector('section.unused')!;
    expect(text(section.querySelector('p.muted'))).toContain('3 of 5');
    const rows = [...section.querySelectorAll('tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map(text),
    );
    expect(rows[0]).toEqual(['500', 'Solaris Urbino 12', 'BKM', 'Thu 12 Nov 08:30', '4', '4 days']);
    expect(rows[2]).toEqual(['502', 'Solaris Urbino 12', 'BKM', '—', '—', 'never recorded']);
  });

  it('shows the first unused buses of a long list until asked for all', () => {
    const { el, render } = renderPage(
      VehicleStats,
      stateAfter(
        vehicleStatsFeature,
        VehicleStatsActions.loaded({ report: report(1, FIRST_ROWS + 3) }),
      ),
    );
    const rows = () => el.querySelectorAll('section.unused tbody tr');
    expect(rows()).toHaveLength(FIRST_ROWS);
    (el.querySelector('section.unused button.more') as HTMLButtonElement).click();
    render();
    expect(rows()).toHaveLength(FIRST_ROWS + 3);
  });

  it('says when every bus served a line', () => {
    const { el } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature, VehicleStatsActions.loaded({ report: report(1, 0) })),
    );
    expect(text(el.querySelector('section.unused tbody'))).toBe(
      'Every bus served a line in this window.',
    );
  });

  it('shows the first rows of a long list until asked for all', () => {
    const { el, render } = renderPage(
      VehicleStats,
      stateAfter(
        vehicleStatsFeature,
        VehicleStatsActions.loaded({ report: report(FIRST_ROWS + 5) }),
      ),
    );
    expect(el.querySelectorAll('table.line-days tbody tr')).toHaveLength(FIRST_ROWS);
    const more = el.querySelector('button.more') as HTMLButtonElement;
    expect(text(more)).toBe(`Show all ${FIRST_ROWS + 5}`);
    more.click();
    render();
    expect(el.querySelectorAll('table.line-days tbody tr')).toHaveLength(FIRST_ROWS + 5);
  });

  it('switches the window through the URL, and loads the new one', () => {
    const { el, store, fixture, setQuery } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature),
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);

    expect(store.dispatch).toHaveBeenCalledWith(
      VehicleStatsActions.load({ window: 'week', agencies: [] }),
    );
    const buttons = [...el.querySelectorAll('.windows > button')] as HTMLButtonElement[];
    expect(buttons.map(text)).toEqual(['Last 24 hours', 'Last 7 days', 'Last 30 days']);
    expect(buttons[1].classList).toContain('active');
    buttons[0].click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { window: 'day' },
      queryParamsHandling: 'merge',
    });

    setQuery({ window: 'day' });
    expect(store.dispatch).toHaveBeenLastCalledWith(
      VehicleStatsActions.load({ window: 'day', agencies: [] }),
    );
  });

  it('chooses operators in a dropdown of checkboxes, through the URL', () => {
    const { el, store, fixture, setQuery } = renderPage(
      VehicleStats,
      stateAfter(vehicleStatsFeature, VehicleStatsActions.loaded({ report: report() })),
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const toggle = el.querySelector('app-agency-picker button.toggle') as HTMLButtonElement;
    expect(text(toggle)).toBe('All operators ▾');
    expect(el.querySelector('app-agency-picker fieldset')).toBeNull();

    toggle.click();
    fixture.detectChanges();
    const boxes = () =>
      [...el.querySelectorAll('app-agency-picker input[type=checkbox]')] as HTMLInputElement[];
    expect(boxes().map((b) => [text(b.parentElement), b.checked])).toEqual([
      ['BKM 2', true],
      ['TUROSN 1', true],
    ]);
    boxes()[1].click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { agency: ['BKM'] },
      queryParamsHandling: 'merge',
    });

    setQuery({ agency: ['BKM'] });
    fixture.detectChanges();
    expect(store.dispatch).toHaveBeenLastCalledWith(
      VehicleStatsActions.load({ window: 'week', agencies: ['BKM'] }),
    );
    expect(text(toggle)).toBe('BKM ▾');
    expect(boxes().map((b) => [b.checked, b.disabled])).toEqual([
      [true, true],
      [false, false],
    ]);
    (el.querySelector('app-agency-picker button.all') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { agency: null },
      queryParamsHandling: 'merge',
    });

    document.body.click();
    fixture.detectChanges();
    expect(el.querySelector('app-agency-picker fieldset')).toBeNull();
  });

  it('keeps the operator selection minimal, empty for all', () => {
    const options = report().agencies.concat({ name: 'WSCHODEXPRESS', vehicles: 5 });
    expect(toggleAgency(options, [], 'TUROSN')).toEqual(['BKM', 'WSCHODEXPRESS']);
    expect(toggleAgency(options, ['WSCHODEXPRESS', 'BKM'], 'TUROSN')).toEqual([]);
    expect(toggleAgency(options, ['BKM'], 'WSCHODEXPRESS')).toEqual(['BKM', 'WSCHODEXPRESS']);
    expect(pickerLabel(options, [])).toBe('All operators');
    expect(pickerLabel(options, ['BKM', 'TUROSN'])).toBe('2 of 3 operators');
  });

  it('reads the window and describes what happened', () => {
    expect(parseWindow('day')).toBe('day');
    expect(parseWindow('year')).toBe('week');
    expect(parseWindow(null)).toBe('week');
    expect(whatHappened(incident(bus(1, '230'), 'disappeared'))).toBe('not seen again that day');
    expect(idleFor('2026-11-16T15:00:00Z', '2026-11-16T20:00:00Z')).toBe('5 h');
    expect(idleFor('2026-11-12T07:30:00Z', '2026-11-16T20:00:00Z')).toBe('4 days');
    expect(idleFor(null, '2026-11-16T20:00:00Z')).toBe('never recorded');
    expect(
      lastSeenNote({
        ...incident(bus(1, '230'), 'disappeared'),
        lastStop: null,
        delaySeconds: -45,
      }),
    ).toBe('0:45 min early');
  });
});

describe('vehicle stats effect', () => {
  const run = (api: Partial<VehicleStatsApi>, window: 'day' | 'week' = 'day') => {
    const actions = new Subject<unknown>();
    const out: unknown[] = [];
    load(actions as unknown as Actions, api as VehicleStatsApi).subscribe((a) => out.push(a));
    actions.next(VehicleStatsActions.load({ window, agencies: ['BKM'] }));
    return out;
  };

  it('loads the report for the window, or reports the failure', () => {
    const r = report();
    const api = { report: vi.fn(() => of(r)) };
    expect(run(api)).toEqual([VehicleStatsActions.loaded({ report: r })]);
    expect(api.report).toHaveBeenCalledWith('day', ['BKM']);

    const failing = {
      report: () =>
        throwError(() => new HttpErrorResponse({ status: 400, error: { detail: 'Bad window' } })),
    };
    expect(run(failing)).toEqual([VehicleStatsActions.failure({ error: 'Bad window' })]);
  });
});
