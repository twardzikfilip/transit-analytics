import { Router } from '@angular/router';
import { FleetReport, FleetVehicle, VehicleDetail, VehicleUsage } from '../../core/models/models';
import { FleetActions } from '../../store/fleet/fleet.actions';
import { fleetFeature } from '../../store/fleet/fleet.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { Fleet } from './fleet';
import { localTime } from '../../shared/format/time';
import {
  dailyBars,
  dayLabel,
  daysOutText,
  isShortWindow,
  percent,
  searchVehicles,
  timelineRows,
  usageBars,
  vehicleName,
} from './fleet-format';

const bus = (
  id: number,
  number: string,
  model: string | null = 'Solaris Urbino 12',
): FleetVehicle => ({
  id,
  number,
  model,
  agency: 'BKM',
  vehicleClass: model ? 'maxi' : null,
});
const usage = (v: FleetVehicle, hours: number, lines: string[] = ['103']): VehicleUsage => ({
  vehicle: v,
  hours,
  days: hours ? 2 : 0,
  lines: hours ? lines : [],
});

const A = bus(1, '230');
const B = bus(2, '1230', 'MAN Lion’s City');
const C = bus(3, '17', null);

function report(): FleetReport {
  return {
    from: '2026-11-10T11:00:00Z',
    to: '2026-11-17T11:00:00Z',
    inUse: 2,
    fleet: 3,
    agencies: [
      { name: 'BKM', vehicles: 3 },
      { name: 'TUROSN', vehicles: 1 },
    ],
    models: [
      { model: 'Solaris Urbino 12', inUse: 1, fleet: 1 },
      { model: 'MAN Lion’s City', inUse: 1, fleet: 1 },
      { model: 'unknown', inUse: 0, fleet: 1 },
    ],
    classes: [
      { model: 'maxi', inUse: 2, fleet: 2 },
      { model: 'unknown', inUse: 0, fleet: 1 },
    ],
    makes: [
      { model: 'Solaris', inUse: 1, fleet: 1 },
      { model: 'MAN', inUse: 1, fleet: 1 },
      { model: 'unknown', inUse: 0, fleet: 1 },
    ],
    mostUsed: [usage(A, 30.5, ['11', '103']), usage(B, 4)],
    leastUsed: [usage(B, 4), usage(A, 30.5)],
    usage: [usage(A, 30.5, ['11', '103']), usage(B, 4), usage(C, 0)],
    breakdowns: [
      {
        vehicle: B,
        at: '2026-11-16T07:04:00Z',
        line: '9',
        brigade: '009-01',
        headsign: 'Dworzec',
        stop: 'Rynek',
        returned: null,
        daysOut: 1.2,
      },
    ],
  };
}

function detail(to = '2026-11-17T11:00:00Z'): VehicleDetail {
  return {
    vehicle: A,
    from: '2026-11-10T11:00:00Z',
    to,
    hours: 3.5,
    lines: ['11', '103'],
    lastSeen: '2026-11-16T20:00:00Z',
    days: [
      {
        day: '2026-11-15',
        start: '2026-11-14T23:00:00Z',
        hours: 1,
        runs: [{ from: 6 * 3600, to: 7 * 3600, line: '103', brigade: '103-05', headsign: 'Rynek' }],
        breaks: [],
      },
      {
        day: '2026-11-16',
        start: '2026-11-15T23:00:00Z',
        hours: 2.5,
        runs: [
          { from: 6 * 3600, to: 7 * 3600, line: '11', brigade: null, headsign: null },
          { from: 8 * 3600, to: 9.5 * 3600, line: '11', brigade: '011-02', headsign: 'Dworzec' },
          { from: 24.5 * 3600, to: 25 * 3600, line: 'N1', brigade: null, headsign: null },
        ],
        breaks: [{ from: 7 * 3600, to: 8 * 3600 }],
      },
    ],
    incidents: [
      {
        kind: 'vehicle-changed',
        day: '2026-11-16',
        line: '11',
        brigade: '011-02',
        headsign: 'Dworzec',
        scheduledDeparture: '2026-11-16T07:00:00Z',
        note: 'Bus 230 wasn’t seen again that day: possibly broken down.',
        story: [
          { at: '2026-11-16T07:01:00Z', text: 'Bus 230 runs line 11.' },
          { at: '2026-11-16T07:10:00Z', text: "Bus 230 stops and doesn't move on." },
          { at: '2026-11-16T07:27:00Z', text: 'Bus 412 takes over.' },
        ],
        timeline: [
          {
            vehicle: '230',
            kind: 'running',
            from: '2026-11-16T07:01:00Z',
            to: '2026-11-16T07:10:00Z',
          },
          {
            vehicle: '230',
            kind: 'standing',
            from: '2026-11-16T07:10:00Z',
            to: '2026-11-16T07:44:00Z',
          },
          {
            vehicle: '412',
            kind: 'approach',
            from: '2026-11-16T07:20:00Z',
            to: '2026-11-16T07:27:00Z',
          },
          {
            vehicle: '412',
            kind: 'service',
            from: '2026-11-16T07:27:00Z',
            to: '2026-11-16T07:50:00Z',
          },
        ],
        paths: [
          {
            vehicle: '230',
            points: [
              [53.13, 23.15],
              [53.132, 23.152],
            ],
          },
          {
            vehicle: '412',
            points: [
              [53.12, 23.14],
              [53.131, 23.151],
              [53.14, 23.16],
            ],
          },
        ],
      },
    ],
  };
}

describe('Fleet helpers', () => {
  it('finds buses by number first, then by model', () => {
    const all = report().usage;
    expect(searchVehicles(all, '230').map((u) => u.vehicle.number)).toEqual(['230', '1230']);
    expect(searchVehicles(all, '23').map((u) => u.vehicle.number)).toEqual(['230', '1230']);
    expect(searchVehicles(all, 'lion').map((u) => u.vehicle.number)).toEqual(['1230']);
    expect(searchVehicles(all, ' ')).toEqual([]);
    expect(searchVehicles(all, '1', 1)).toHaveLength(1);
  });

  it('describes buses, breakdowns and shares', () => {
    expect(vehicleName(A)).toBe('230 · Solaris Urbino 12');
    expect(vehicleName(C)).toBe('17');
    const b = report().breakdowns[0];
    expect(daysOutText(b)).toBe('not back yet (1.2 days)');
    expect(daysOutText({ ...b, returned: '2026-11-17T05:00:00Z', daysOut: 1 })).toBe(
      'back after 1 day',
    );
    expect(percent(2, 3)).toBe(67);
    expect(percent(1, 0)).toBe(0);
    expect(dayLabel('2026-11-16')).toBe('Mon 16 Nov');
    expect(isShortWindow('2026-11-16T11:00:00Z', '2026-11-17T11:00:20Z')).toBe(true);
    expect(isShortWindow('2026-11-14T11:00:00Z', '2026-11-17T11:00:00Z')).toBe(false);
  });

  it('builds the timeline per day, or per run for a short window', () => {
    const d = detail();
    const perDay = timelineRows(d, false);
    expect(perDay.map((r) => r.label)).toEqual(['Sun 15 Nov', 'Mon 16 Nov']);
    expect(perDay[1].runs.map((r) => r.text)).toEqual(['11', '11', 'N1']);
    expect(perDay[1].runs[1].detail).toBe('line 11, brigade 011-02 → Dworzec');
    expect(perDay[1].breaks).toHaveLength(1);

    const perRun = timelineRows(d, true);
    expect(perRun.map((r) => r.label)).toEqual(['103 06:00', '11 06:00', '11 08:00', 'N1 00:30']);
    // The second day's runs follow on from the first's: a day (86 400 s) later.
    expect(perRun[1].runs[0].from).toBe(86_400 + 6 * 3600);
    expect(perRun[3].runs[0].text).toBe('N1');
    expect(timelineRows({ ...d, days: [] }, true)).toEqual([]);
  });

  it('makes bars of the hours per bus and per day, marking days with abnormal trips', () => {
    const bars = usageBars(report().usage);
    expect(bars.map((b) => [b.key, b.value])).toEqual([
      [1, 30.5],
      [2, 4],
      [3, 0],
    ]);
    expect(bars[0].detail).toBe('Solaris Urbino 12 · lines 11, 103');
    expect(bars[2].detail).toBe('not in service');
    const days = dailyBars(detail());
    expect(days.map((b) => [b.label, b.value, !!b.marked])).toEqual([
      ['15 Nov', 1, false],
      ['16 Nov', 2.5, true],
    ]);
    expect(days[0].detail).toBe('1 runs');
    expect(days[1].detail).toBe('1 abnormal trip');
  });
});

describe('Fleet page', () => {
  const URL = { from: '2026-11-10T12:00', to: '2026-11-17T12:00' };
  const WINDOW = { from: new Date(URL.from).toISOString(), to: new Date(URL.to).toISOString() };

  it('loads the window and operators in the URL and shows the tiles and every bus', () => {
    const { el, store } = renderPage(
      Fleet,
      stateAfter(fleetFeature, FleetActions.loaded({ report: report() })),
      { ...URL, agency: 'BKM' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(
      FleetActions.load({ window: WINDOW, agencies: ['BKM'] }),
    );
    expect(text(el.querySelector('.in-use .hero'))).toBe('2 of 3');
    expect(text(el.querySelector('.in-use'))).toContain('67% of the fleet');
    const bars = (tile: string) => [...el.querySelectorAll(`.${tile} .model-bars li`)].map(text);
    expect(bars('models')).toEqual(['Solaris Urbino 121/1', 'MAN Lion’s City1/1', 'unknown0/1']);
    // By size class, named with their lengths, and by make.
    expect(bars('classes')).toEqual(['Maxi (11–15 m)2/2', 'Unknown size0/1']);
    expect(el.querySelector('.classes .model-bars li')!.getAttribute('title')).toBe(
      'Maxi (11–15 m): 2 of 2 in use',
    );
    expect(bars('makes')).toEqual(['Solaris1/1', 'MAN1/1', 'unknown0/1']);
    const lists = [...el.querySelectorAll('.tile .mini-list')];
    expect([...lists[0].querySelector('li')!.children].map(text)).toEqual([
      '230',
      'Solaris Urbino 12',
      '30.5 h',
      '11, 103',
    ]);
    expect(text(lists[1].querySelector('li'))).toContain('1230');
    expect(text(el.querySelector('.breakdowns .hero'))).toBe('1');
    expect(text(el.querySelector('.breakdowns li'))).toContain('not back yet (1.2 days)');
    expect(el.querySelectorAll('app-bar-chart rect.bar')).toHaveLength(3);
    expect(text(el.querySelector('.vehicle'))).toContain('Search for a bus');
    expect(text(el.querySelector('app-agency-picker'))).toContain('BKM');
  });

  it('searches for a bus and shows it, loading it for the window', () => {
    const { el, fixture, render, setQuery, store } = renderPage(
      Fleet,
      stateAfter(fleetFeature, FleetActions.loaded({ report: report() })),
      URL,
    );
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const input = el.querySelector('.search input') as HTMLInputElement;
    input.value = 'lion';
    input.dispatchEvent(new Event('input'));
    render();
    const options = [...el.querySelectorAll('.matches button')];
    expect(options.map(text)).toEqual(['1230MAN Lion’s City · BKM4.0 h']);
    (options[0] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { vehicle: 2 },
      queryParamsHandling: 'merge',
    });

    input.value = 'nothing';
    input.dispatchEvent(new Event('input'));
    render();
    expect(text(el.querySelector('.search'))).toContain('No bus matches “nothing”.');
    input.value = '230';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { vehicle: 1 },
      queryParamsHandling: 'merge',
    });

    // A click on a bar or a name in the tiles chooses the bus too.
    (el.querySelectorAll('app-bar-chart rect.hit')[2] as SVGElement).dispatchEvent(
      new Event('click'),
    );
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { vehicle: 3 },
      queryParamsHandling: 'merge',
    });

    setQuery({ ...URL, vehicle: '1' });
    expect(store.dispatch).toHaveBeenLastCalledWith(
      FleetActions.loadVehicle({ id: 1, window: WINDOW }),
    );
    const loads = vi.mocked(store.dispatch).mock.calls.length;
    setQuery({ ...URL, vehicle: '1' });
    expect(vi.mocked(store.dispatch).mock.calls).toHaveLength(loads);
    setQuery(URL);
    expect(store.dispatch).toHaveBeenLastCalledWith(FleetActions.clearVehicle());
  });

  it('tells each breakdown of the bus step by step, with both buses on a time line and a map', async () => {
    useSvgMaps();
    const { el, fixture } = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.loaded({ report: report() }),
        FleetActions.vehicleLoaded({ vehicle: detail() }),
      ),
      { ...URL, vehicle: '1' },
    );
    await fixture.whenStable();
    fixture.detectChanges();
    // Open at first; it folds away and back.
    const story = () => el.querySelector('.detail tr.story-row app-incident-story');
    const toggle = el.querySelector('.detail button.story-toggle') as HTMLButtonElement;
    expect(text(toggle)).toBe('▾ Step by step (3 steps)');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect([...story()!.querySelectorAll('ol.steps li')].map(text)).toEqual([
      `${localTime('2026-11-16T07:01:00Z')} Bus 230 runs line 11.`,
      `${localTime('2026-11-16T07:10:00Z')} Bus 230 stops and doesn't move on.`,
      `${localTime('2026-11-16T07:27:00Z')} Bus 412 takes over.`,
    ]);
    // A lane per bus, a bar per stretch, the steps numbered on the axis.
    expect([...story()!.querySelectorAll('.lane-label')].map(text)).toEqual([
      'Steps',
      'Bus 230',
      'Bus 412',
      '',
    ]);
    const bars = [...story()!.querySelectorAll('.bar')] as HTMLElement[];
    expect(bars.map((b) => b.className.replace('bar ', ''))).toEqual([
      'running',
      'standing',
      'approach',
      'service',
    ]);
    expect(bars[1].title).toBe(
      `Bus 230: standing still, ${localTime('2026-11-16T07:10:00Z')}–${localTime('2026-11-16T07:44:00Z')}`,
    );
    expect(parseFloat(bars[0].style.left)).toBeLessThan(parseFloat(bars[1].style.left));
    expect([...story()!.querySelectorAll('.step')].map(text)).toEqual(['1', '2', '3']);
    expect([...story()!.querySelectorAll('app-incident-timeline .legend li')].map(text)).toEqual([
      'On the trip',
      'Standing still',
      'On its way, no brigade',
      'On the brigade',
    ]);
    expect(story()!.querySelectorAll('.tick').length).toBeGreaterThan(1);
    // Both buses on a map: the one that broke down in orange, ringed where last seen; the other in blue.
    const map = story()!.querySelector('app-path-map')!;
    expect([...map.querySelectorAll('.legend li')].map(text)).toEqual([
      'Bus 230 (broke down)',
      'Bus 412 (took over)',
    ]);
    const strokes = [...map.querySelectorAll('.leaflet-overlay-pane path')].map((p) =>
      p.getAttribute('stroke'),
    );
    expect(strokes).toEqual(['#eb6834', '#eb6834', '#1e88e5']);

    toggle.click();
    fixture.detectChanges();
    expect(story()).toBeNull();
    expect(text(toggle)).toBe('▸ Step by step (3 steps)');
    toggle.click();
    fixture.detectChanges();
    expect(story()).not.toBeNull();
  });

  it("shows a bus's runs per day, hours per day and abnormal trips", () => {
    const { el, fixture } = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.loaded({ report: report() }),
        FleetActions.vehicleLoaded({ vehicle: detail() }),
      ),
      { ...URL, vehicle: '1' },
    );
    expect(text(el.querySelector('.detail-header'))).toContain('Solaris Urbino 12');
    expect(text(el.querySelector('.detail-header'))).toContain(
      '3.5 h in service on 2 days · lines 11, 103',
    );
    expect(text(el.querySelector('.detail'))).toContain('Runs per day');
    expect([...el.querySelectorAll('app-run-timeline text.name')].map(text)).toEqual([
      'Sun 15 Nov',
      'Mon 16 Nov',
    ]);
    expect(el.querySelectorAll('app-run-timeline rect.run')).toHaveLength(4);
    expect(el.querySelectorAll('.detail app-bar-chart circle.mark')).toHaveLength(1);
    const row = [...el.querySelectorAll('.detail table tbody tr td')].map(text);
    expect(row[0]).toBe('Mon 16 Nov');
    expect(row[3]).toBe('Broke down');

    const run = el.querySelector('app-run-timeline rect.run') as SVGElement;
    run.dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();
    expect(text(el.querySelector('app-run-timeline .chart-tooltip'))).toBe(
      '06:00–07:00line 103, brigade 103-05 → Rynek',
    );

    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    (el.querySelector('.detail .close') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { vehicle: null },
      queryParamsHandling: 'merge',
    });
  });

  it('shows the runs one per row for a window of a day, and a bus without trouble', () => {
    const short = detail('2026-11-11T11:00:00Z');
    const { el } = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.loaded({ report: report() }),
        FleetActions.vehicleLoaded({ vehicle: { ...short, incidents: [] } }),
      ),
      { ...URL, vehicle: '1' },
    );
    expect(text(el.querySelector('.detail h4'))).toBe('Runs');
    expect(el.querySelectorAll('app-run-timeline text.name')).toHaveLength(4);
    expect(text(el.querySelector('.detail tbody'))).toContain('It broke down on no trip');
  });

  it('says when a bus served no line in the window', () => {
    const short = detail('2026-11-11T11:00:00Z');
    const idle = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.loaded({ report: report() }),
        FleetActions.vehicleLoaded({ vehicle: { ...short, days: [], incidents: [] } }),
      ),
      { ...URL, vehicle: '1' },
    );
    expect(text(idle.el.querySelector('.detail'))).toContain(
      'This bus served no line in this window.',
    );
  });

  it('shows errors and loading', () => {
    const { el } = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.loaded({ report: { ...report(), usage: [], mostUsed: [], leastUsed: [] } }),
        FleetActions.load({ window: WINDOW, agencies: [] }),
        FleetActions.loadVehicle({ id: 9, window: WINDOW }),
        FleetActions.vehicleFailure({ error: 'Vehicle 9 not found' }),
      ),
      URL,
    );
    expect(text(el.querySelector('[role=status]'))).toBe('Loading…');
    expect(text(el.querySelector('.vehicle [role=alert]'))).toBe('Vehicle 9 not found');
    expect(text(el.querySelector('.tile .mini-list'))).toBe('No bus in service in this window.');
    expect(text(el)).toContain('No buses.');
  });
});
