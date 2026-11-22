import { frequency } from './rankings';

describe('Route rankings', () => {
  it('gives the time between departures in hours and minutes', () => {
    expect(frequency(7.5)).toBe('every 7.5 min');
    expect(frequency(25.4)).toBe('every 25 min');
    expect(frequency(59.6)).toBe('every 1 h');
    expect(frequency(75)).toBe('every 1 h 15 min');
    expect(frequency(120)).toBe('every 2 h');
  });
});
