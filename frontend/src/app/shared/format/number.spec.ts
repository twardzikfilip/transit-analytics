import { area, density, int, km, kmh, percent, shareOf } from './number';

describe('number formatting', () => {
  it('groups whole numbers as in Poland', () => {
    expect(int(13243.4)).toBe((13243).toLocaleString('pl-PL'));
  });

  it('shows shares in the ways the pages need', () => {
    expect(percent(0.7399)).toBe('74.0%');
    expect(percent(0.5, { digits: 0 })).toBe('50%');
    expect(percent(0.042, { adaptive: true })).toBe('4.2%');
    expect(percent(0.42, { adaptive: true })).toBe('42%');
    expect(percent(0, { adaptive: true })).toBe('0%');
    expect(percent(0.0004, { tiny: true })).toBe('<0.1%');
    expect(percent(0, { tiny: true })).toBe('0.0%');
    expect(shareOf(9, 10, { digits: 0 })).toBe('90%');
    expect(shareOf(1, 0)).toBe('–');
    expect(shareOf(1, 0, { empty: '—' })).toBe('—');
  });

  it('shows distances, speeds, areas and densities', () => {
    expect(km(12.345)).toBe('12.3 km');
    expect(kmh(17.64)).toBe('17.6 km/h');
    expect(area(0.083)).toBe('8.3 ha');
    expect(area(7.2)).toBe('7.20 km²');
    expect(density(5794.4)).toBe(`${(5794).toLocaleString('pl-PL')} /km²`);
  });
});
