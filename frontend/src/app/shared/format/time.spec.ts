import { clock, duration, formatDelay, hourTickStep, localTime } from './time';

describe('time formatting', () => {
  it('formats delays as m:ss', () => {
    expect(formatDelay(62)).toBe('1:02');
    expect(formatDelay(-30)).toBe('−0:30');
    expect(formatDelay(null)).toBe('—');
  });

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

  it('picks an hour tick step that fits', () => {
    expect(hourTickStep(24, 12)).toBe(2);
    expect(hourTickStep(24, 5)).toBe(6);
  });

  it('gives local Białystok time of an instant', () => {
    expect(localTime('2026-11-09T16:19:52Z')).toBe('17:19');
  });
});
