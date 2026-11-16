import { LineRef, Segment } from '../../core/models/models';
import {
  clock,
  duration,
  groupLines,
  hourRange,
  hourTickStep,
  slowestSegments,
  spellsText,
  subtypeLabel,
  variantKindLabel,
} from './format';

describe('route detail formatting', () => {
  it('formats clock times and wraps after midnight', () => {
    expect(clock(3 * 3600 + 40 * 60)).toBe('03:40');
    expect(clock(24 * 3600 + 14 * 60)).toBe('00:14');
    expect(clock(21 * 3600 + 27 * 60 + 40)).toBe('21:28');
  });

  it('formats durations in minutes or hours', () => {
    expect(duration(45)).toBe('45 min');
    expect(duration(244)).toBe('4 h 04');
    expect(duration(60)).toBe('1 h 00');
  });

  it('groups lines by subtype in display order', () => {
    const line = (name: string, subtype: LineRef['subtype']): LineRef => ({
      id: 1,
      line: name,
      name,
      subtype,
    });
    const groups = groupLines([
      line('1', 'urban'),
      line('N1', 'night_urban'),
      line('100', 'suburban'),
      line('2', 'urban'),
    ]);
    expect(groups.map((g) => g.label)).toEqual(['Urban', 'Suburban', 'Night']);
    expect(groups[0].lines.map((l) => l.name)).toEqual(['1', '2']);
    expect(subtypeLabel('night_urban')).toBe('Night');
  });

  it('picks the slowest measured segments', () => {
    const seg = (kmh: number | null): Segment => ({ from: 'a', to: 'b', fromKm: 0, toKm: 1, kmh });
    expect(slowestSegments([seg(30), seg(null), seg(12), seg(20), seg(12), seg(40)])).toEqual([
      2, 4, 3,
    ]);
    expect(slowestSegments([seg(null)])).toEqual([]);
  });

  it('pads the hour range and picks a tick step that fits', () => {
    expect(hourRange([3 * 3600 + 40 * 60, 24 * 3600 + 14 * 60])).toEqual({ from: 2, to: 26 });
    expect(hourRange([])).toEqual({ from: 4, to: 24 });
    expect(hourTickStep(24, 12)).toBe(2);
    expect(hourTickStep(24, 5)).toBe(6);
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
