import { bandLabel, bandRows, formatShare, windowLabel } from './delay-bands';

describe('delay bands', () => {
  it('labels on time, the bands between, and the open-ended last band', () => {
    expect(bandLabel({ fromMinutes: null, toMinutes: 2, vehicles: 0, reports: 0 })).toBe(
      'On time (under 2 min late, or early)',
    );
    expect(bandLabel({ fromMinutes: 2, toMinutes: 5, vehicles: 0, reports: 0 })).toBe(
      'More than 2 min (2–5)',
    );
    expect(bandLabel({ fromMinutes: 10, toMinutes: null, vehicles: 0, reports: 0 })).toBe(
      'More than 10 min',
    );
  });

  it('computes each band’s share of reports', () => {
    const rows = bandRows([
      { fromMinutes: null, toMinutes: 1, vehicles: 5, reports: 75 },
      { fromMinutes: 1, toMinutes: 2, vehicles: 2, reports: 25 },
    ]);
    expect(rows.map((r) => r.share)).toEqual([0.75, 0.25]);
    expect(bandRows([{ fromMinutes: null, toMinutes: 1, vehicles: 0, reports: 0 }])[0].share).toBe(
      0,
    );
  });

  it('never shows a non-zero share as 0.0%', () => {
    expect(formatShare(4 / 9544)).toBe('<0.1%');
    expect(formatShare(0)).toBe('0.0%');
    expect(formatShare(0.603)).toBe('60.3%');
  });

  it('labels the window, with dates only when it spans days', () => {
    const local = (h: number, m: number, d = 16) => new Date(2026, 10, d, h, m).toISOString();
    expect(windowLabel(local(8, 36), local(14, 36), 'en-GB')).toBe('Mon 16 Nov, 08:36 – 14:36');
    expect(windowLabel(local(14, 14, 9), local(14, 14), 'en-GB')).toBe(
      'Mon 9 Nov, 14:14 – Mon 16 Nov, 14:14',
    );
  });
});
