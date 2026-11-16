import { MIN_GAP_MS, nearestIndex, niceTicks, segments, timeTicks } from './chart-geometry';

describe('chart geometry', () => {
  it('makes round y ticks from zero', () => {
    expect(niceTicks(197)).toEqual([0, 50, 100, 150, 200]);
    expect(niceTicks(205)).toEqual([0, 50, 100, 150, 200, 250]);
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(0)).toEqual([0, 1]);
    expect(niceTicks(2, 5, -0.5)).toEqual([-0.5, 0, 0.5, 1, 1.5, 2]);
    expect(niceTicks(0, 5, -3)).toEqual([-3, -2, -1, 0]);
  });

  it('breaks the line where buckets are missing', () => {
    const p = (min: number) => ({ time: min * 60_000, value: 1 });
    const runs = segments([p(0), p(1), p(2), p(5), p(6), p(9)], 60_000);
    expect(runs.map((r) => r.map((x) => x.time / 60_000))).toEqual([[0, 1, 2], [5, 6], [9]]);
  });

  it('bridges a single missed 20-second poll, but not a longer pause', () => {
    const p = (s: number) => ({ time: s * 1000, value: 1 });
    const runs = segments([p(0), p(20), p(60), p(80), p(200), p(220)], 20_000, MIN_GAP_MS);
    expect(runs.map((r) => r.map((x) => x.time / 1000))).toEqual([
      [0, 20, 60, 80],
      [200, 220],
    ]);
  });

  it('picks round time ticks within the limit', () => {
    const from = new Date(2026, 10, 16, 5, 37).getTime();
    const to = new Date(2026, 10, 16, 17, 37).getTime();
    const { ticks, interval } = timeTicks(from, to, 8);
    expect(interval).toBe(2 * 3_600_000);
    expect(new Date(ticks[0]).getHours()).toBe(6);
    expect(ticks.length).toBeLessThanOrEqual(8);
  });

  it('finds the nearest point', () => {
    const pts = [0, 10, 20, 30].map((t) => ({ time: t, value: t }));
    expect(nearestIndex(pts, 14)).toBe(1);
    expect(nearestIndex(pts, 16)).toBe(2);
    expect(nearestIndex(pts, -5)).toBe(0);
    expect(nearestIndex(pts, 99)).toBe(3);
    expect(nearestIndex([], 1)).toBe(-1);
  });
});
