import { LineStats } from '../../core/models/models';
import { nextSort, sortLines, topLines } from './sort';

describe('line stats sorting', () => {
  const line = (route: string, lengthKm: number | null): LineStats => ({
    routeId: 1,
    route,
    color: null,
    textColor: null,
    lengthKm,
    shortestTravelSeconds: null,
    longestTravelSeconds: null,
    peakSpeedKmh: null,
    offPeakSpeedKmh: null,
    peakIntervalMinutes: null,
    offPeakIntervalMinutes: null,
    peakRegularityCv: null,
    offPeakRegularityCv: null,
    peakVehicles: 0,
    offPeakVehicles: 0,
    trips: 0,
  });
  const lines = [line('10', 12), line('N1', null), line('2', 20), line('100', 12)];

  it('sorts line names naturally', () => {
    expect(sortLines(lines, { key: 'route', descending: false }).map((l) => l.route)).toEqual([
      '2',
      '10',
      '100',
      'N1',
    ]);
  });

  it('sorts by a value both ways, ties by line, missing values last', () => {
    expect(sortLines(lines, { key: 'lengthKm', descending: false }).map((l) => l.route)).toEqual([
      '10',
      '100',
      '2',
      'N1',
    ]);
    expect(sortLines(lines, { key: 'lengthKm', descending: true }).map((l) => l.route)).toEqual([
      '2',
      '10',
      '100',
      'N1',
    ]);
  });

  it('takes the top lines by a value, skipping lines without it', () => {
    expect(topLines(lines, 'lengthKm', true, 2).map((l) => l.route)).toEqual(['2', '10']);
    expect(topLines(lines, 'lengthKm', false).map((l) => l.route)).toEqual(['10', '100', '2']);
  });

  it('flips direction on the same column', () => {
    expect(nextSort({ key: 'lengthKm', descending: false }, 'lengthKm')).toEqual({
      key: 'lengthKm',
      descending: true,
    });
    expect(nextSort({ key: 'lengthKm', descending: true }, 'peakVehicles')).toEqual({
      key: 'peakVehicles',
      descending: false,
    });
  });
});
