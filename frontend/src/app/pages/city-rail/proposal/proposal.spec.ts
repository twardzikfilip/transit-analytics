import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { CityRailProposal, ProposalLine } from '../../../core/models/models';
import { CityRailActions } from '../../../store/city-rail/city-rail.actions';
import { cityRailFeature } from '../../../store/city-rail/city-rail.reducer';
import { renderPage, stateAfter, text, useSvgMaps } from '../../../testing/page-testing';
import { Proposal } from './proposal';
import { ProposalOverview } from './proposal-overview';
import { ProposalSolutionPage } from './proposal-solution';

const HUB = { name: 'Białystok', kind: 'served' as const, lat: 53.1336, lon: 23.1354, km: 0 };

function line(id: string, to: string, conflicts = 0): ProposalLine {
  return {
    id,
    stations: [
      { name: 'Białystok', kind: 'served', km: 0 },
      { name: 'Białystok Białostoczek', kind: 'new', km: 1.7 },
      { name: to, kind: 'served', km: 7.9 },
    ],
    points: [
      [53.1336, 23.1354],
      [53.1983, 23.177],
    ],
    km: 7.93,
    spacingKm: 3.96,
    runMinutes: 8,
    runMinutesBack: 9,
    trainsPerDay: 18,
    maxIntervalMinutes: 60,
    vehicles: 2,
    minTurnaround: 9,
    closestMinutes: conflicts ? 9 : 10,
    conflicts,
    out: [
      { times: ['05:20', '05:23', '05:28'], conflict: false },
      { times: ['06:20', '06:23', '06:28'], conflict: conflicts > 0 },
    ],
    back: [{ times: ['05:44', '05:49', '05:53'], conflict: false }],
  };
}

const PROPOSAL: CityRailProposal = {
  day: '2026-11-27',
  rules: {
    headwayMinutes: 10,
    turnaroundMinutes: 5,
    intervalMinutes: 60,
    firstDeparture: '05:00',
    lastDeparture: '23:00',
    newStationDwell: 60,
    hubDwell: 120,
    trainsConsidered: 129,
  },
  corridors: [
    {
      id: 'wasilkow',
      ref: '6',
      served: true,
      stations: [
        HUB,
        { name: 'Białystok Białostoczek', kind: 'new', lat: 53.1469, lon: 23.1457, km: 1.7 },
        { name: 'Wasilków', kind: 'served', lat: 53.1983, lon: 23.177, km: 7.9 },
      ],
      points: [
        [53.1336, 23.1354],
        [53.1983, 23.177],
      ],
    },
    {
      id: 'kuriany',
      ref: '37',
      served: false,
      stations: [
        HUB,
        { name: 'Białystok Fabryczny', kind: 'unserved', lat: 53.1405, lon: 23.1842, km: 3.7 },
        { name: 'Kuriany', kind: 'unserved', lat: 53.1112, lon: 23.3214, km: 13.5 },
      ],
      points: [
        [53.1336, 23.1354],
        [53.1112, 23.3214],
      ],
    },
  ],
  solutions: [
    { id: 'radial', lines: [line('S4', 'Wasilków'), line('S5', 'Kuriany', 1)] },
    { id: 'through', lines: [line('S1', 'Wasilków')] },
  ],
};

const loaded = () =>
  stateAfter(
    cityRailFeature,
    CityRailActions.loadProposal(),
    CityRailActions.proposalLoaded({ proposal: PROPOSAL }),
  );

/** The solution page's route: which solution, and the URL's query. */
function solutionRoute(solution: string, query: Record<string, string> = {}) {
  const params = convertToParamMap(query);
  return {
    provide: ActivatedRoute,
    useValue: {
      data: of({ solution }),
      queryParamMap: new BehaviorSubject(params).asObservable(),
      snapshot: { data: { solution }, queryParamMap: params },
    },
  };
}

describe('City rail proposal', () => {
  it('loads the proposal and the railways, and has a tab for each of its pages', () => {
    const { el, store } = renderPage(Proposal, stateAfter(cityRailFeature));

    expect(store.dispatch).toHaveBeenCalledWith(CityRailActions.load());
    expect(store.dispatch).toHaveBeenCalledWith(CityRailActions.loadProposal());
    expect([...el.querySelectorAll('nav.tabs a')].map((a) => a.getAttribute('href'))).toEqual([
      '/city-rail/proposal-1',
      '/city-rail/proposal-1/radial',
      '/city-rail/proposal-1/through',
    ]);
  });

  it('lists each railway with its stations, the new ones and those without trains today marked', () => {
    useSvgMaps();
    const { el } = renderPage(ProposalOverview, loaded());

    expect(el.querySelectorAll('th.corridor')).toHaveLength(2);
    expect(text(el.querySelectorAll('th.corridor')[1])).toContain('Białystok – Kuriany');
    expect(text(el.querySelectorAll('th.corridor')[1])).toContain('No passenger trains today');
    expect(text(el.querySelector('.tag.new')?.closest('tr'))).toContain('Białystok Białostoczek');
    expect(el.querySelectorAll('.tag.unserved')).toHaveLength(2);
    expect(text(el.querySelector('.rules'))).toContain('at least 10 minutes');
    expect(text(el.querySelector('.rules'))).toContain('129 trains');
    // The new stations are named on the map.
    expect(text(el.querySelector('app-proposal-map'))).toContain('Białystok Białostoczek');
  });

  it('shows a solution: its lines with their figures, and the first line’s timetable both ways', () => {
    useSvgMaps();
    const { el } = renderPage(ProposalSolutionPage, loaded(), {}, [solutionRoute('radial')]);

    const rows = [...el.querySelectorAll('table.lines tbody tr')];
    expect(rows.map((r) => text(r.querySelector('td')))).toEqual([
      'S4 Białystok – Wasilków',
      'S5 Białystok – Kuriany',
    ]);
    expect(text(rows[0])).toContain('7.9 km');
    expect(text(rows[0])).toContain('8 / 9 min');
    expect(text(rows[0])).toContain('59 km/h');
    expect(rows[1].querySelector('.conflict')).not.toBeNull();
    expect(rows[0].classList).toContain('selected');

    const ways = [...el.querySelectorAll('.timetable h3')].map(text);
    expect(ways).toEqual(['To Wasilków', 'To Białystok']);
    const out = el.querySelector('table.times') as HTMLElement;
    expect([...out.querySelectorAll('th')].map(text)).toEqual([
      'Białystok',
      'Białystok Białostoczek New',
      'Wasilków',
    ]);
    expect([...out.querySelectorAll('tr:first-child td')].map(text)).toEqual(['05:20', '06:20']);
  });

  it('shows the line chosen in the URL, with its trains too close to another marked', () => {
    useSvgMaps();
    const { el } = renderPage(ProposalSolutionPage, loaded(), {}, [
      solutionRoute('radial', { line: 'S5' }),
    ]);

    expect(text(el.querySelector('.timetable h2'))).toContain('S5 Białystok – Kuriany');
    expect(el.querySelectorAll('table.times td.conflict')).toHaveLength(3);
    expect(text(el.querySelector('.timetable .intro .conflict'))).toContain('1');
  });

  it('chooses a line by clicking its row, in the URL', () => {
    useSvgMaps();
    const { el } = renderPage(ProposalSolutionPage, loaded(), {}, [solutionRoute('radial')]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    (el.querySelectorAll('table.lines tbody tr')[1] as HTMLElement).click();

    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { line: 'S5' },
      queryParamsHandling: 'merge',
    });
  });

  it('says so while the trains are not loaded', () => {
    useSvgMaps();
    const empty = { ...PROPOSAL, day: null, solutions: [] };
    const state = stateAfter(cityRailFeature, CityRailActions.proposalLoaded({ proposal: empty }));
    const { el } = renderPage(ProposalSolutionPage, state, {}, [solutionRoute('radial')]);

    expect(text(el)).toContain('not loaded yet');
  });
});
