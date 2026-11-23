import { boardingRule, formatGtfsTime } from './gtfs';

describe('gtfs display helpers', () => {
  it('formats times, including ones past midnight', () => {
    expect(formatGtfsTime('04:49:00')).toBe('04:49');
    expect(formatGtfsTime('24:05:00')).toBe('00:05 +1');
    expect(formatGtfsTime('49:00:00')).toBe('01:00 +2');
    expect(formatGtfsTime(null)).toBe('—');
  });

  it('names boarding rules', () => {
    expect(boardingRule(0)).toBeNull();
    expect(boardingRule(3)).toBe('on request');
  });
});
