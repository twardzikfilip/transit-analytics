import { RouteRef, Segment } from '../../core/models/models';
import {
  capacityLabel,
  geometryLabel,
  hourRange,
  routeTable,
  slowestSegments,
  spellsText,
  subtypeLabel,
  variantKindLabel,
} from './format';

describe('route detail formatting', () => {
  it('puts the lines in a table of kinds of line by geometry', () => {
    const route = (
      name: string,
      subtype: RouteRef['subtype'],
      geometry: RouteRef['geometry'] = 'radial',
    ): RouteRef => ({
      id: 1,
      route: name,
      name,
      subtype,
      geometry,
      capacityClass: null,
      operatingAgency: null,
    });
    const table = routeTable([
      route('1', 'urban', 'diameter'),
      route('N1', 'night_urban'),
      route('100', 'suburban', 'feeder'),
      route('2', 'urban', 'diameter'),
      route('5', 'urban'),
    ]);
    expect(table.columns.map((c) => c.label)).toEqual([
      'Radial',
      'Diameter',
      'Arcantial',
      'Tangential',
      'Feeder',
    ]);
    expect(table.columns[3].description).toContain('around the centre');
    expect(table.rows.map((r) => r.label)).toEqual(['Urban', 'Suburban', 'Night']);
    expect(table.rows[0].cells.map((c) => c.map((l) => l.name))).toEqual([
      ['5'],
      ['1', '2'],
      [],
      [],
      [],
    ]);
    expect(table.rows[1].cells[4].map((l) => l.name)).toEqual(['100']);

    const withRegional = routeTable([route('1', 'urban'), route('700', 'regional', null)]);
    expect(withRegional.columns.at(-1)).toEqual({
      label: 'Not classified',
      description: "Other operators' lines: not classified.",
    });
    expect(withRegional.rows[1].cells.at(-1)!.map((l) => l.name)).toEqual(['700']);
    expect(subtypeLabel('night_urban')).toBe('Night');
    expect(geometryLabel('arcantial')).toBe('Arcantial');
    expect(capacityLabel('tertiary')).toBe('Tertiary');
    expect(capacityLabel(null)).toBe('Not classified');
  });

  it('picks the slowest measured segments', () => {
    const seg = (kmh: number | null): Segment => ({ from: 'a', to: 'b', fromKm: 0, toKm: 1, kmh });
    expect(slowestSegments([seg(30), seg(null), seg(12), seg(20), seg(12), seg(40)])).toEqual([
      2, 4, 3,
    ]);
    expect(slowestSegments([seg(null)])).toEqual([]);
  });

  it('pads the hour range', () => {
    expect(hourRange([3 * 3600 + 40 * 60, 24 * 3600 + 14 * 60])).toEqual({ from: 2, to: 26 });
    expect(hourRange([])).toEqual({ from: 4, to: 24 });
  });

  it('names variant kinds', () => {
    expect(variantKindLabel('depot')).toBe('Depot run');
    expect(variantKindLabel('main')).toBe('Base route');
  });

  it('describes bus changes within a brigade day in local time', () => {
    const spells = [
      { number: '224', from: '2026-11-09T06:37:05Z', to: '2026-11-09T16:19:52Z' },
      { number: '221', from: '2026-11-09T16:26:03Z', to: '2026-11-09T18:44:14Z' },
      { number: '036', from: '2026-11-09T18:50:04Z', to: '2026-11-09T20:27:46Z' },
    ];
    expect(spellsText(spells)).toBe('224 until 17:19 → 221 17:26–19:44 → 036 from 19:50');
    expect(spellsText(spells.slice(0, 2))).toBe('224 until 17:19 → 221 from 17:26');
  });
});
