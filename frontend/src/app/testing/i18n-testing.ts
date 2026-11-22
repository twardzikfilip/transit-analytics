import { EnvironmentProviders } from '@angular/core';
import { Translation, TranslocoLoader, provideTransloco } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import de from '../../i18n/de.json';
import en from '../../i18n/en.json';
import pl from '../../i18n/pl.json';
import { LANGS } from '../core/i18n/t';

const FILES: Record<string, Translation> = { en, pl, de };

/** The translation files, at once (no HTTP). */
class FileLoader implements TranslocoLoader {
  getTranslation(lang: string): Observable<Translation> {
    return of(FILES[lang]);
  }
}

/** Transloco for unit tests: every language's file, English shown. */
export function provideTestI18n(): EnvironmentProviders[] {
  return provideTransloco({
    config: { availableLangs: LANGS, defaultLang: 'en', fallbackLang: 'en' },
    loader: FileLoader,
  });
}
