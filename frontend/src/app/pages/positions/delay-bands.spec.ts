import {
  bandLabel,
  bandRows,
  formatDelay,
  formatShare,
  isOutlier,
  windowLabel,
} from './delay-bands';

describe('delay bands', () => {
  it('labels the open-ended first and last bands', () => {
    expect(bandLabel({ fromMinutes: null, toMinutes: 1, vehicles: 0, reports: 0 })).toBe(
      'Under 1 min (on time or early)',
    );
    expect(bandLabel({ fromMinutes: 3, toMinutes: 4, vehicles: 0, reports: 0 })).toBe('3–4 min');
    expect(bandLabel({ fromMinutes: 10, toMinutes: null, vehicles: 0, reports: 0 })).toBe(
      '10 min or more',
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

  it('formats delays as m:ss', () => {
    expect(formatDelay(62)).toBe('1:02');
    expect(formatDelay(-30)).toBe('−0:30');
    expect(formatDelay(null)).toBe('—');
  });

  it('labels the window, with dates only when it spans days', () => {
    const local = (h: number, m: number, d = 16) => new Date(2026, 10, d, h, m).toISOString();
    expect(windowLabel(local(8, 36), local(14, 36), 'en-GB')).toBe('Mon 16 Nov, 08:36 – 14:36');
    expect(windowLabel(local(14, 14, 9), local(14, 14), 'en-GB')).toBe(
      'Mon 9 Nov, 14:14 – Mon 16 Nov, 14:14',
    );
  });

  it('treats delays over 30 minutes as outliers', () => {
    expect(isOutlier(30 * 60)).toBe(false);
    expect(isOutlier(30 * 60 + 1)).toBe(true);
    expect(isOutlier(-45 * 60)).toBe(false);
    expect(isOutlier(null)).toBe(false);
  });
});
