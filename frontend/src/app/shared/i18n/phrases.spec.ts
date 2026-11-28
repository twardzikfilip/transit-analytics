import { TestBed } from '@angular/core/testing';
import { Language } from '../../core/i18n/language';
import { Phrase } from '../../core/models/models';
import { provideTestI18n } from '../../testing/i18n-testing';
import { phrasesText } from './phrases';

const change: Phrase[] = [
  { key: 'busChanged', params: { bus: '319', other: '412', index: 10, total: 25 } },
  { key: 'detour', params: { meters: 1500 } },
];

describe('phrases from the backend', () => {
  async function speak(lang: 'en' | 'pl' | 'fr'): Promise<void> {
    TestBed.configureTestingModule({ providers: [provideTestI18n()] });
    const language = TestBed.inject(Language);
    language.set(lang);
    await language.ready();
  }

  it('writes short phrases in English, one after another', async () => {
    await speak('en');
    expect(phrasesText(change)).toBe('Bus 319 → 412 at stop 10/25 · Detour 1,500 m');
    expect(phrasesText(null)).toBe('');
  });

  it('follows the language: its words and its numbers', async () => {
    await speak('pl');
    expect(phrasesText(change)).toBe('Autobus 319 → 412 na przystanku 10/25 · Objazd 1500 m');
    localStorage.removeItem('language');
  });

  it('writes French numbers its way', async () => {
    await speak('fr');
    expect(phrasesText([{ key: 'busLate', params: { bus: '7', n: 12 } }])).toBe(
      'Bus 7 en retard de 12 min',
    );
    localStorage.removeItem('language');
  });
});
