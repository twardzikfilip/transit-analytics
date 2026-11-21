import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { NetworkCoverageApi } from '../../core/api/api.services';
import { AreaShape, DistrictDensity, NetworkCoverage } from '../../core/models/models';
import { renderPage, text, useSvgMaps } from '../../testing/page-testing';
import {
  DENSITY_CLASSES,
  area,
  density,
  densityClassLabel,
  densityColor,
  percent,
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

function district(
  name: string,
  shape: AreaShape,
  extra: Partial<DistrictDensity> = {},
): DistrictDensity {
  return {
    name,
    shape,
    areaKm2: 2,
    population: 10000,
    density: 5000,
    forestKm2: 0,
    landKm2: 2,
    landDensity: 5000,
    largeForest: false,
    coveredShare: 0.9,
    ...extra,
  };
}

const DATA: NetworkCoverage = {
  radiusMeters: 400,
  stops: [
    [53.13, 23.16],
    [53.14, 23.17],
  ],
  cellMeters: 10,
  city: { name: 'Białystok', shape: square(53.1, 23.1, 0.1), areaKm2: 101.94 },
  coverage: { areaKm2: 101.94, coveredKm2: 75.42, share: 0.7399 },
  withoutForest: { areaKm2: 80.59, coveredKm2: 66.33, share: 0.823 },
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
    district('Piaski', square(53.13, 23.15)),
    district('Dojlidy', square(53.13, 23.16), {
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
  officialPopulation: 289217,
  sources: { shapes: 'OSM', shapesFetched: '2026-11-20', population: 'city' },
  belt: {
    distanceKm: 5,
    cellMeters: 20,
    places: [
      {
        name: 'Kleosin',
        gmina: 'gmina Juchnowiec Kościelny',
        kind: 'village',
        shape: square(53.09, 23.12),
        areaKm2: 1.09,
        population: 4564,
        populationDate: '2023-12-31',
        populationSource: 'Gmina Juchnowiec Kościelny',
        density: 4187,
        forestKm2: 0.05,
        landKm2: 1.04,
        landDensity: 4395,
        largeForest: false,
        coveredShare: 0.95,
      },
      {
        name: 'Turczyn',
        gmina: 'gmina Choroszcz',
        kind: 'village',
        shape: square(53.11, 23.03),
        areaKm2: 3.2,
        population: null,
        populationDate: null,
        populationSource: null,
        density: null,
        forestKm2: 0,
        landKm2: 3.2,
        landDensity: null,
        largeForest: false,
        coveredShare: 0.2,
      },
    ],
    woods: [square(53.1, 23.05, 0.005)],
  },
};

describe('Network coverage page', () => {
  it('shows the coverage with and without the woods, the woods, and the densities', () => {
    useSvgMaps();
    const { el, fixture } = renderPage(NetworkCoveragePage, {}, {}, [
      { provide: NetworkCoverageApi, useValue: { coverage: () => of(DATA) } },
    ]);
    const tiles = [...el.querySelectorAll('.tile strong')].map(text);
    expect(tiles).toEqual(['101.94 km²', '74.0%', '21.35 km²', '82.3%']);
    expect(text(el.querySelector('table tbody tr'))).toContain('Las Solnicki');
    expect(el.querySelector('table tbody tr:last-child')?.classList).toContain('muted');

    const rows = [...el.querySelectorAll('table.districts tbody tr')].map((r) =>
      text(r.querySelector('td')),
    );
    expect(rows).toEqual(['Dojlidy', 'Piaski']);
    expect(text(el.querySelector('table.districts tfoot tr'))).toContain('13 243');
    expect(text(el.querySelector('table.districts tr.official'))).toContain('289 217');

    const wooded = el.querySelectorAll('table.wooded tbody tr');
    expect(wooded).toHaveLength(1);
    expect(text(wooded[0])).toContain('463 /km²');
    expect(text(wooded[0])).toContain('+100%');
    expect(el.querySelectorAll('.leaflet-container')).toHaveLength(2);

    // The coverage map rolls up to its title, and down again.
    const toggle = el.querySelector('.coverage-card .collapse-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('app-coverage-map')).toBeNull();
    expect(text(el.querySelector('.coverage-card'))).toBe('▾ Area near a stop and the woods');
    toggle.click();
    fixture.detectChanges();
    expect(el.querySelector('app-coverage-map .leaflet-container')).not.toBeNull();
    // The woods are hatched: filled with a pattern of horizontal lines.
    const wood = el.querySelector('.leaflet-woods-pane path');
    const id = wood?.getAttribute('fill')?.match(/url\(#(.+)\)/)?.[1];
    expect(id).toBeTruthy();
    expect(el.querySelector(`.leaflet-woods-pane pattern#${id} line`)).not.toBeNull();

    // The density map: districts on the scale by their density without woods; the woods as no residents.
    const fills = [...el.querySelectorAll('.leaflet-district-fills-pane path')];
    expect(fills.slice(0, 2).map((f) => f.getAttribute('fill'))).toEqual([
      densityColor(5000),
      densityColor(463),
    ]);
    expect(el.querySelector('.leaflet-district-woods-pane path')?.getAttribute('fill')).toMatch(
      /^url\(#no-people-hatch/,
    );
    expect(text(el.querySelector('app-district-map .scale'))).toContain('0, woods');

    // The belt: on the same map and scale; a village without a figure white and dashed, and last in its table.
    expect(fills).toHaveLength(4);
    const beltFills = [...el.querySelectorAll('.leaflet-district-fills-pane path')].slice(2);
    expect(beltFills.map((f) => f.getAttribute('fill'))).toEqual([densityColor(4395), '#ffffff']);
    expect(beltFills[1].getAttribute('stroke-dasharray')).toBe('3 3');
    const beltRows = [...el.querySelectorAll('table.belt tbody tr')];
    expect(beltRows.map((r) => text(r.querySelector('td')))).toEqual(['Kleosin', 'Turczyn']);
    expect(text(beltRows[0])).toContain('31.12.2023');
    expect(text(beltRows[1])).toContain('no figure');
    expect(text(el.querySelector('app-district-map .scale'))).toContain('No figure');
  });

  it('says when it fails', () => {
    const { el } = renderPage(NetworkCoveragePage, {}, {}, [
      {
        provide: NetworkCoverageApi,
        useValue: { coverage: () => throwError(() => new HttpErrorResponse({ status: 500 })) },
      },
    ]);
    expect(el.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('formats and colours', () => {
    expect(percent(0.7399)).toBe('74.0%');
    expect(area(0.083)).toBe('8.3 ha');
    expect(area(7.2)).toBe('7.20 km²');
    expect(density(5794.4)).toBe('5794 /km²'.replace('5794', (5794).toLocaleString('pl-PL')));
    // Density classes: light to dark, the last open-ended.
    expect(densityColor(120)).toBe(DENSITY_CLASSES[0].color);
    expect(densityColor(463)).toBe(DENSITY_CLASSES[1].color);
    expect(densityColor(2500)).toBe(DENSITY_CLASSES[3].color);
    expect(densityColor(13719)).toBe(DENSITY_CLASSES[5].color);
    expect(densityClassLabel(DENSITY_CLASSES[5])).toBe(
      `${(10000).toLocaleString('pl-PL')} and more`,
    );
  });
});
