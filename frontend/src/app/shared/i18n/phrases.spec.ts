import { TestBed } from '@angular/core/testing';
import { Language } from '../../core/i18n/language';
import { Phrase } from '../../core/models/models';
import { provideTestI18n } from '../../testing/i18n-testing';
import { phrasesText } from './phrases';

const takeover: Phrase[] = [
  {
    key: 'stepTakesOver',
    params: {
      bus: '412',
      joined: { key: 'stopsFurtherOn', params: { n: 5 } },
      stop: 'Lipowa',
      index: 10,
      total: 25,
      away: { key: 'kilometres', params: { n: 1.5 } },
      other: '319',
    },
  },
  { key: 'stepNoBusFor', params: { n: 17 } },
];

describe('phrases from the backend', () => {
  async function speak(lang: 'en' | 'pl' | 'fr'): Promise<void> {
    TestBed.configureTestingModule({ providers: [provideTestI18n()] });
    const language = TestBed.inject(Language);
    language.set(lang);
    await language.ready();
  }

  it('writes phrases in English, with the parts inside them and counts in their forms', async () => {
    await speak('en');
    expect(phrasesText(takeover)).toBe(
      'Bus 412 takes over 5 stops further on, heading to Lipowa (stop 10 of 25), 1.5 km from where bus 319 ' +
        'stopped. The trip had no bus for 17 min.',
    );
    expect(phrasesText(null)).toBe('');
  });

  it('follows the language: its words, its plural forms and its numbers', async () => {
    await speak('pl');
    expect(phrasesText(takeover)).toBe(
      'Autobus 412 przejmuje kurs 5 przystanków dalej, w drodze do przystanku Lipowa (10 z 25), 1,5 km od ' +
        'miejsca, gdzie stanął autobus 319. Kurs był bez autobusu przez 17 min.',
    );
    localStorage.removeItem('language');
  });

  it('writes French decimals with a comma', async () => {
    await speak('fr');
    expect(phrasesText([{ key: 'kilometres', params: { n: 4.2 } }])).toBe('4,2 km');
    localStorage.removeItem('language');
  });
});
