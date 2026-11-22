import { TranslocoService } from '@jsverse/transloco';
import { Lang, useLanguage } from '../../core/i18n/t';
import { area, decimal, density, int, km, kmh, num, percent, shareOf } from './number';

/** Numbers only need the language, not its texts. */
const speak = (lang: Lang) => useLanguage({} as TranslocoService, lang);

describe('number formatting', () => {
  it('groups and writes decimals as the chosen language does', () => {
    expect(int(13243.4)).toBe('13,243');
    expect(decimal(19.84)).toBe('19.8');
    expect(num(2.5)).toBe('2.5');
    expect(num(3)).toBe('3');
    speak('pl');
    expect(int(13243.4)).toBe((13243).toLocaleString('pl-PL'));
    expect(decimal(19.84)).toBe('19,8');
    expect(km(12.345)).toBe('12,3 km');
    speak('de');
    expect(int(13243.4)).toBe('13.243');
    expect(decimal(0.02, 2)).toBe('0,02');
    speak('fr');
    expect(decimal(19.84)).toBe('19,8');
    expect(percent(0.7399)).toBe(
      (0.7399).toLocaleString('fr-FR', { style: 'percent', minimumFractionDigits: 1 }),
    );
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
    expect(density(5794.4)).toBe('5,794 /km²');
  });
});
