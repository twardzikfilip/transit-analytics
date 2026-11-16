import { Corridor } from '../../core/models/models';
import { DEFAULT_SORT, nextCorridorSort, sortCorridors } from './sort';

const corridor = (from: string, buses: number, peakCv: number | null, stops = 4): Corridor => ({
  from,
  to: 'Z',
  stops: Array.from({ length: stops }, (_, i) => `S${i}`),
  lines: ['1', '2'],
  throughLines: ['1', '2'],
  km: 1,
  buses,
  oneWay: false,
  peakIntervalMin: 5,
  offPeakIntervalMin: 8,
  peakCv,
  offPeakCv: null,
});

describe('corridor sorting', () => {
  const list = [corridor('B', 100, 0.5), corridor('A', 300, null, 6), corridor('C', 200, 0.2)];

  it('puts the busiest first by default', () => {
    expect(sortCorridors(list, DEFAULT_SORT).map((c) => c.from)).toEqual(['A', 'C', 'B']);
  });

  it('keeps corridors without a value last in both directions', () => {
    expect(sortCorridors(list, { key: 'peakCv', descending: false }).map((c) => c.from)).toEqual([
      'C',
      'B',
      'A',
    ]);
    expect(sortCorridors(list, { key: 'peakCv', descending: true }).map((c) => c.from)).toEqual([
      'B',
      'C',
      'A',
    ]);
  });

  it('sorts by number of stops and by name', () => {
    expect(sortCorridors(list, { key: 'stops', descending: true })[0].from).toBe('A');
    expect(sortCorridors(list, { key: 'from', descending: false }).map((c) => c.from)).toEqual([
      'A',
      'B',
      'C',
    ]);
  });

  it('starts counts high first and flips the active column', () => {
    expect(nextCorridorSort(DEFAULT_SORT, 'km')).toEqual({ key: 'km', descending: true });
    expect(nextCorridorSort(DEFAULT_SORT, 'peakCv')).toEqual({ key: 'peakCv', descending: false });
    expect(nextCorridorSort(DEFAULT_SORT, 'buses')).toEqual({ key: 'buses', descending: false });
  });
});
