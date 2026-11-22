import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import localePl from '@angular/common/locales/pl';
import {
  EnvironmentProviders,
  inject,
  isDevMode,
  makeEnvironmentProviders,
  provideAppInitializer,
} from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';
import { I18nLoader, Language } from './language';
import { LANGS } from './t';

registerLocaleData(localePl);
registerLocaleData(localeDe);

/**
 * Translations (Transloco, files in src/i18n) and the language chosen in Settings, loaded before the first page so
 * it never shows the wrong language first.
 */
export function provideI18n(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideTransloco({
      config: {
        availableLangs: LANGS,
        defaultLang: 'en',
        fallbackLang: 'en',
        reRenderOnLangChange: true,
        missingHandler: { useFallbackTranslation: true, logMissingKey: isDevMode() },
        prodMode: !isDevMode(),
      },
      loader: I18nLoader,
    }),
    provideAppInitializer(() => inject(Language).ready()),
  ]);
}
