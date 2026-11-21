import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { NetworkCoverageApi } from '../../core/api/api.services';
import { AreaShape, NetworkCoverage, PlaceDensity } from '../../core/models/models';
import { DENSITY_SCALE } from '../../shared/palette';
import { renderPage, text, useSvgMaps } from '../../testing/page-testing';
import {
  asOf,
  densityColor,
  densityStepLabel,
  densityTooltip,
  labelMinKm2,
} from './coverage-format';
import { NetworkCoveragePage } from './network-coverage';

const square = (lat: number, lon: number, size = 0.01): AreaShape => ({
  outer: [
    [
      [lat, lon],
      [lat, lon + size],
      [lat + size, lon + size],
      [lat + size, lon],
      [lat, lon],
    ],
  ],
  inner: [],
});

function place(name: string, shape: AreaShape, extra: Partial<PlaceDensity> = {}): PlaceDensity {
  return {
    name,
    kind: 'district',
    gmina: null,
    shape,
    areaKm2: 2,
    population: 10000,
    populationDate: '2025-12-31',
    populationSource: 'city register',
    density: 5000,
    forestKm2: 0,
    landKm2: 2,
    landDensity: 5000,
    largeForest: false,
    coveredShares: [0.9, 0.95, 0.98],
    ...extra,
  };
}

const coverage = (radius: number, share: number, withoutForest: number) => ({
  radiusMeters: radius,
  city: { areaKm2: 101.94, coveredKm2: 101.94 * share, share },
  withoutForest: { areaKm2: 80.59, coveredKm2: 80.59 * withoutForest, share: withoutForest },
});

const DATA: NetworkCoverage = {
  stops: [
    [53.13, 23.16],
    [53.14, 23.17],
  ],
  cellMeters: 10,
  city: { name: 'Białystok', shape: square(53.1, 23.1, 0.1), areaKm2: 101.94 },
  coverages: [coverage(400, 0.7399, 0.823), coverage(500, 0.81, 0.88), coverage(600, 0.86, 0.92)],
  forests: [
    {
      name: 'Las Solnicki',
      named: true,
      district: 'Dojlidy',
      areaKm2: 7.2,
      pieces: 1,
      shapes: [square(53.1, 23.2)],
    },
    { name: 'Smaller woods', named: false, district: null, areaKm2: 0.42, pieces: 30, shapes: [] },
  ],
  forestKm2: 21.35,
  districts: [
    place('Piaski', square(53.13, 23.15)),
    place('Dojlidy', square(53.13, 23.16), {
      areaKm2: 13.96,
      population: 3243,
      density: 232,
      forestKm2: 6.95,
      landKm2: 7.01,
      landDensity: 463,
      largeForest: true,
    }),
  ],
  forestShare: 0.1,
  official: { population: 289217, date: '2025-12-31', source: 'GUS' },
  sources: { shapes: 'OSM', shapesFetched: '2026-11-20', population: 'city' },
  belt: {
    distanceKm: 5,
    cellMeters: 20,
    places: [
      place('Kleosin', square(53.09, 23.12), {
        kind: 'village',
        gmina: 'gmina Juchnowiec Kościelny',
        areaKm2: 1.09,
        population: 4564,
        populationDate: '2023-12-31',
        populationSource: 'Gmina Juchnowiec Kościelny',
        density: 4187,
        forestKm2: 0.05,
        landKm2: 1.04,
        landDensity: 4395,
      }),
      place('Turczyn', square(53.11, 23.03), {
        kind: 'village',
        gmina: 'gmina Choroszcz',
        areaKm2: 3.2,
        population: null,
        populationDate: null,
        populationSource: null,
        density: null,
        landKm2: 3.2,
        landDensity: null,
        coveredShares: [0.2, 0.25, 0.3],
      }),
    ],
    woods: [square(53.1, 23.05, 0.005)],
  },
};

function render() {
  useSvgMaps();
  return renderPage(NetworkCoveragePage, {}, {}, [
    { provide: NetworkCoverageApi, useValue: { coverage: () => of(DATA) } },
  ]);
}

describe('Network coverage page', () => {
  it('has an identical section for each walking distance, each map rolling up on its own', () => {
    const { el, fixture } = render();
    const sections = [...el.querySelectorAll('section.radius')];
    expect(sections.map((s) => text(s.querySelector('h2')))).toEqual([
      'Stops within 400 m',
      'Stops within 500 m',
      'Stops within 600 m',
    ]);
    const tiles = (s: Element) => [...s.querySelectorAll('.tile strong')].map(text);
    expect(tiles(sections[0])).toEqual(['101.94 km²', '74.0%', '21.35 km²', '82.3%']);
    expect(tiles(sections[1])).toEqual(['101.94 km²', '81.0%', '21.35 km²', '88.0%']);
    expect(text(sections[2].querySelectorAll('.tile .muted')[2])).toBe(
      'Within 600 m of the nearest bus stop',
    );
    expect(el.querySelectorAll('app-coverage-map')).toHaveLength(3);
    expect(text(sections[1].querySelector('app-coverage-map .legend'))).toContain(
      'Within 500 m of the nearest bus stop',
    );

    // Rolling up the 500 m map leaves the others.
    const toggle = sections[1].querySelector('.collapse-toggle') as HTMLButtonElement;
    expect(text(toggle)).toBe('▾ Area within 500 m of the nearest bus stop, and the woods');
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(sections[1].querySelector('app-coverage-map')).toBeNull();
    expect(el.querySelectorAll('app-coverage-map')).toHaveLength(2);
    toggle.click();
    fixture.detectChanges();
    expect(sections[1].querySelector('app-coverage-map .leaflet-container')).not.toBeNull();

    // The woods are hatched, listed once.
    const wood = el.querySelector('.leaflet-woods-pane path');
    const id = wood?.getAttribute('fill')?.match(/url\(#(.+)\)/)?.[1];
    expect(el.querySelector(`.leaflet-woods-pane pattern#${id} line`)).not.toBeNull();
    expect(text(el.querySelector('table.data-table tbody tr'))).toContain('Las Solnicki');
  });

  it('lists districts and villages with their densities and coverage at each distance', () => {
    const { el } = render();
    const rows = [...el.querySelectorAll('table.districts tbody tr')];
    expect(rows.map((r) => text(r.querySelector('td')))).toEqual(['Dojlidy', 'Piaski']);
    expect([...rows[1].querySelectorAll('td')].slice(-3).map(text)).toEqual([
      '90.0%',
      '95.0%',
      '98.0%',
    ]);
    expect(text(el.querySelector('table.districts tfoot tr'))).toContain('13 243');
    expect(text(el.querySelector('table.districts tr.official'))).toContain('289 217');

    const wooded = el.querySelectorAll('table.wooded tbody tr');
    expect(wooded).toHaveLength(1);
    expect(text(wooded[0])).toContain('463 /km²');
    expect(text(wooded[0])).toContain('+100%');

    const belt = [...el.querySelectorAll('table.belt tbody tr')];
    expect(belt.map((r) => text(r.querySelector('td')))).toEqual(['Kleosin', 'Turczyn']);
    expect(text(belt[0])).toContain('31.12.2023');
    expect(text(belt[1])).toContain('no figure');

    // The density map: places on the scale by their density without woods; the woods as no residents; places
    // without a figure white and dashed.
    const fills = [...el.querySelectorAll('.leaflet-fills-pane path')];
    expect(fills.map((f) => f.getAttribute('fill'))).toEqual([
      densityColor(5000),
      densityColor(463),
      densityColor(4395),
      '#ffffff',
    ]);
    expect(fills[3].getAttribute('stroke-dasharray')).toBe('3 3');
    expect(
      el.querySelector('app-district-map .leaflet-woods-pane path')?.getAttribute('fill'),
    ).toMatch(/^url\(#no-people-hatch/);
    const legend = text(el.querySelector('app-district-map .legend'));
    expect(legend).toContain('0, woods');
    expect(legend).toContain('No figure');
  });

  it('says when it fails', () => {
    useSvgMaps();
    const { el } = renderPage(NetworkCoveragePage, {}, {}, [
      {
        provide: NetworkCoverageApi,
        useValue: { coverage: () => throwError(() => new HttpErrorResponse({ status: 500 })) },
      },
    ]);
    expect(el.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('colours densities, names steps and dates, and words tooltips', () => {
    expect(densityColor(120)).toBe(DENSITY_SCALE[0].color);
    expect(densityColor(463)).toBe(DENSITY_SCALE[1].color);
    expect(densityColor(2500)).toBe(DENSITY_SCALE[3].color);
    expect(densityColor(13719)).toBe(DENSITY_SCALE[5].color);
    expect(densityStepLabel(DENSITY_SCALE[5])).toBe(`${(10000).toLocaleString('pl-PL')} and more`);
    expect(labelMinKm2(14)).toBe(0);
    expect(labelMinKm2(11)).toBe(6);
    expect(asOf('2021-03-31')).toBe('2021 census');
    expect(asOf('2023-12-31')).toBe('31.12.2023');
    expect(asOf('2012')).toBe('2012');
    expect(densityTooltip(DATA.belt.places[1])).toContain('No population figure found');
    expect(densityTooltip(DATA.districts[1])).toContain('outside the woods');
    expect(densityTooltip(DATA.belt.places[0])).toContain('(village, gmina Juchnowiec Kościelny)');
  });
});
